package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	configPath  = "/data/adb/live_dashboard/config.json"
	dataDir     = "/data/adb/live_dashboard"
	socketName  = "@live_dashboard"
	maxBodySize = 512 * 1024
)

type Config struct {
	Enabled          bool         `json:"enabled"`
	ServerURL        string       `json:"server_url"`
	Token            string       `json:"token"`
	HeartbeatSeconds int          `json:"heartbeat_seconds"`
	ReportActivity   bool         `json:"report_activity"`
	ReportBattery    bool         `json:"report_battery"`
	ReportHealth     bool         `json:"report_health"`
	CustomRules      []CustomRule `json:"custom_rules"`
}

type CustomRule struct {
	PackageName       string `json:"package_name"`
	CustomAppName     string `json:"custom_app_name"`
	CustomDescription string `json:"custom_description,omitempty"`
}

type Event struct {
	Type          string         `json:"type"`
	PackageName   string         `json:"package_name,omitempty"`
	AppName       string         `json:"app_name,omitempty"`
	Activity      string         `json:"activity,omitempty"`
	Title         string         `json:"title,omitempty"`
	Artist        string         `json:"artist,omitempty"`
	MusicApp      string         `json:"music_app,omitempty"`
	TimestampMS   int64          `json:"timestamp_ms,omitempty"`
	HealthRecords []HealthRecord `json:"records,omitempty"`
}

type HealthRecord struct {
	Type        string  `json:"type"`
	Value       float64 `json:"value"`
	Unit        string  `json:"unit"`
	TimestampMS int64   `json:"timestamp_ms"`
	EndTimeMS   *int64  `json:"end_time_ms,omitempty"`
}

type daemon struct {
	mu              sync.Mutex
	config          Config
	configHash      [32]byte
	lastConsentHash [32]byte
	foreground      Event
	music           Event
	pendingHealth   []HealthRecord
	lastReport      time.Time
	lastError       string
	httpClient      *http.Client
	wake            chan struct{}
}

func main() {
	log.SetFlags(log.Ldate | log.Ltime | log.LUTC)
	if err := os.MkdirAll(dataDir, 0700); err != nil {
		log.Fatal(err)
	}
	d := &daemon{
		httpClient: &http.Client{Timeout: 20 * time.Second},
		wake:       make(chan struct{}, 1),
	}
	d.reloadConfig()
	d.loadPendingHealth()
	go d.reportLoop()
	if err := d.listen(); err != nil {
		log.Fatal(err)
	}
}

func (d *daemon) listen() error {
	_ = os.Remove(socketName)
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: socketName, Net: "unix"})
	if err != nil {
		return fmt.Errorf("listen: %w", err)
	}
	defer listener.Close()
	listener.SetUnlinkOnClose(true)
	log.Printf("listening on abstract socket %s", socketName)
	for {
		conn, err := listener.AcceptUnix()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return nil
			}
			log.Printf("accept: %v", err)
			continue
		}
		go d.handleConnection(conn)
	}
}

func (d *daemon) handleConnection(conn *net.UnixConn) {
	defer conn.Close()
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	reader := io.LimitReader(conn, maxBodySize)
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 4096), maxBodySize)
	for scanner.Scan() {
		var event Event
		if err := json.Unmarshal(scanner.Bytes(), &event); err != nil {
			continue
		}
		d.acceptEvent(event)
	}
}

func (d *daemon) acceptEvent(event Event) {
	if event.TimestampMS <= 0 {
		event.TimestampMS = time.Now().UnixMilli()
	}
	d.mu.Lock()
	switch event.Type {
	case "foreground":
		if event.PackageName != "" {
			d.foreground = event
		}
	case "music":
		d.music = event
	case "health":
		for _, record := range event.HealthRecords {
			if validHealthRecord(record) {
				d.pendingHealth = appendUniqueHealth(d.pendingHealth, record)
			}
		}
		if len(d.pendingHealth) > 1000 {
			d.pendingHealth = d.pendingHealth[len(d.pendingHealth)-1000:]
		}
		d.persistPendingHealthLocked()
	}
	d.mu.Unlock()
	select {
	case d.wake <- struct{}{}:
	default:
	}
}

func (d *daemon) reportLoop() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
		case <-d.wake:
		}
		d.reloadConfig()
		d.reportOnce()
	}
}

func (d *daemon) reloadConfig() {
	raw, err := os.ReadFile(configPath)
	if err != nil {
		return
	}
	hash := sha256.Sum256(raw)
	d.mu.Lock()
	if hash == d.configHash {
		d.mu.Unlock()
		return
	}
	d.mu.Unlock()

	var cfg Config
	if err := json.Unmarshal(raw, &cfg); err != nil {
		d.setError("invalid config")
		return
	}
	cfg.ServerURL = strings.TrimRight(strings.TrimSpace(cfg.ServerURL), "/")
	cfg.Token = strings.TrimSpace(cfg.Token)
	if cfg.HeartbeatSeconds < 10 {
		cfg.HeartbeatSeconds = 10
	}
	if cfg.HeartbeatSeconds > 300 {
		cfg.HeartbeatSeconds = 300
	}
	d.mu.Lock()
	d.config = cfg
	d.configHash = hash
	d.mu.Unlock()
	log.Printf("configuration reloaded (enabled=%t)", cfg.Enabled)
}

func (d *daemon) reportOnce() {
	d.mu.Lock()
	cfg := d.config
	configHash := d.configHash
	lastConsentHash := d.lastConsentHash
	foreground := d.foreground
	music := d.music
	pending := append([]HealthRecord(nil), d.pendingHealth...)
	lastReport := d.lastReport
	d.mu.Unlock()

	if !cfg.Enabled || cfg.ServerURL == "" || cfg.Token == "" {
		d.writeStatus(cfg, false)
		return
	}

	if configHash != lastConsentHash {
		if err := d.postConsent(cfg); err != nil {
			d.setError(err.Error())
			d.writeStatus(cfg, false)
			return
		}
		d.mu.Lock()
		d.lastConsentHash = configHash
		d.mu.Unlock()
	}

	healthOK := true
	if cfg.ReportHealth && len(pending) > 0 {
		batchSize := min(500, len(pending))
		if err := d.postHealth(cfg, pending[:batchSize]); err != nil {
			healthOK = false
			d.setError(err.Error())
		} else {
			d.mu.Lock()
			d.removeHealthLocked(pending[:batchSize])
			d.persistPendingHealthLocked()
			d.mu.Unlock()
		}
	}

	interval := time.Duration(cfg.HeartbeatSeconds) * time.Second
	reportOK := true
	if cfg.ReportActivity && foreground.PackageName != "" && time.Since(lastReport) >= interval {
		if err := d.postReport(cfg, foreground, music); err != nil {
			reportOK = false
			d.setError(err.Error())
		} else {
			d.mu.Lock()
			d.lastReport = time.Now()
			d.lastError = ""
			d.mu.Unlock()
		}
	}
	d.writeStatus(cfg, healthOK && reportOK)
}

func (d *daemon) postConsent(cfg Config) error {
	scopes := []string{"network_state"}
	if cfg.ReportActivity {
		scopes = append(scopes, "usage_stats")
	}
	if cfg.ReportBattery {
		scopes = append(scopes, "battery")
	}
	if cfg.ReportHealth {
		scopes = append(scopes, "xiaomi_health", "sleep", "heart_rate", "steps")
	}
	return d.postJSON(cfg, "/api/consent", map[string]any{
		"consent_version":    1,
		"activity_reporting": cfg.ReportActivity,
		"health_reporting":   cfg.ReportHealth,
		"granted_scopes":     scopes,
	})
}

func (d *daemon) postReport(cfg Config, foreground, music Event) error {
	extra := map[string]any{"network_type": networkType()}
	appName := foreground.AppName
	if appName == "" {
		appName = foreground.PackageName
	}
	if cfg.ReportBattery {
		if percent, ok := readIntFile("/sys/class/power_supply/battery/capacity"); ok {
			extra["battery_percent"] = percent
		}
		if status, err := os.ReadFile("/sys/class/power_supply/battery/status"); err == nil {
			s := strings.ToLower(strings.TrimSpace(string(status)))
			extra["battery_charging"] = s == "charging" || s == "full"
		}
	}
	if rule, ok := matchCustomRule(cfg.CustomRules, foreground.PackageName); ok {
		if rule.CustomAppName != "" {
			extra["custom_app_name"] = truncate(rule.CustomAppName, 64)
			appName = rule.CustomAppName
		}
		if rule.CustomDescription != "" {
			extra["custom_description"] = truncate(rule.CustomDescription, 256)
		}
	}
	if music.Title != "" && time.Since(time.UnixMilli(music.TimestampMS)) < 15*time.Minute {
		extra["music"] = map[string]any{
			"title":  truncate(music.Title, 256),
			"artist": truncate(music.Artist, 256),
			"app":    truncate(music.MusicApp, 64),
		}
	}
	return d.postJSON(cfg, "/api/report", map[string]any{
		"app_id":       foreground.PackageName,
		"window_title": truncate(appName, 256),
		"timestamp":    time.UnixMilli(foreground.TimestampMS).UTC().Format(time.RFC3339Nano),
		"extra":        extra,
	})
}

func (d *daemon) postHealth(cfg Config, records []HealthRecord) error {
	items := make([]map[string]any, 0, len(records))
	for _, record := range records {
		item := map[string]any{
			"type":      record.Type,
			"value":     record.Value,
			"unit":      truncate(record.Unit, 20),
			"timestamp": time.UnixMilli(record.TimestampMS).UTC().Format(time.RFC3339Nano),
		}
		if record.EndTimeMS != nil {
			item["end_time"] = time.UnixMilli(*record.EndTimeMS).UTC().Format(time.RFC3339Nano)
		}
		items = append(items, item)
	}
	return d.postJSON(cfg, "/api/health-data", map[string]any{"records": items})
}

func (d *daemon) postJSON(cfg Config, path string, payload any) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	request, err := http.NewRequest(http.MethodPost, cfg.ServerURL+path, bytes.NewReader(body))
	if err != nil {
		return err
	}
	request.Header.Set("Authorization", "Bearer "+cfg.Token)
	request.Header.Set("Content-Type", "application/json; charset=utf-8")
	request.Header.Set("User-Agent", "live-dashboard-root-daemon/1.0.0")
	response, err := d.httpClient.Do(request)
	if err != nil {
		return fmt.Errorf("%s: %w", path, err)
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("%s: HTTP %d", path, response.StatusCode)
	}
	return nil
}

func (d *daemon) writeStatus(cfg Config, connected bool) {
	d.mu.Lock()
	status := map[string]any{
		"enabled":        cfg.Enabled,
		"connected":      connected,
		"last_report_ms": d.lastReport.UnixMilli(),
		"pending_health": len(d.pendingHealth),
		"last_error":     d.lastError,
		"updated_ms":     time.Now().UnixMilli(),
	}
	d.mu.Unlock()
	writeJSONAtomic(filepath.Join(dataDir, "status.json"), status, 0600)
}

func (d *daemon) setError(message string) {
	d.mu.Lock()
	d.lastError = truncate(message, 240)
	d.mu.Unlock()
}

func (d *daemon) loadPendingHealth() {
	raw, err := os.ReadFile(filepath.Join(dataDir, "pending-health.json"))
	if err != nil {
		return
	}
	var records []HealthRecord
	if json.Unmarshal(raw, &records) == nil {
		d.mu.Lock()
		d.pendingHealth = records
		d.mu.Unlock()
	}
}

func (d *daemon) persistPendingHealthLocked() {
	writeJSONAtomic(filepath.Join(dataDir, "pending-health.json"), d.pendingHealth, 0600)
}

func (d *daemon) removeHealthLocked(sent []HealthRecord) {
	keys := make(map[string]int, len(sent))
	for _, record := range sent {
		keys[healthKey(record)]++
	}
	remaining := d.pendingHealth[:0]
	for _, record := range d.pendingHealth {
		key := healthKey(record)
		if keys[key] > 0 {
			keys[key]--
			continue
		}
		remaining = append(remaining, record)
	}
	d.pendingHealth = remaining
}

func appendUniqueHealth(records []HealthRecord, incoming HealthRecord) []HealthRecord {
	key := healthKey(incoming)
	for _, existing := range records {
		if healthKey(existing) == key {
			return records
		}
	}
	return append(records, incoming)
}

func healthKey(record HealthRecord) string {
	end := int64(0)
	if record.EndTimeMS != nil {
		end = *record.EndTimeMS
	}
	return record.Type + "|" + strconv.FormatFloat(record.Value, 'g', -1, 64) + "|" + record.Unit + "|" + strconv.FormatInt(record.TimestampMS, 10) + "|" + strconv.FormatInt(end, 10)
}

func validHealthRecord(record HealthRecord) bool {
	return record.Type != "" && record.Unit != "" && record.TimestampMS > 0
}

func matchCustomRule(rules []CustomRule, packageName string) (CustomRule, bool) {
	packageName = strings.TrimSpace(packageName)
	for _, rule := range rules {
		if strings.EqualFold(strings.TrimSpace(rule.PackageName), packageName) {
			return rule, true
		}
	}
	return CustomRule{}, false
}

func networkType() string {
	if interfaceUp("wlan0") {
		return "wifi"
	}
	paths, _ := filepath.Glob("/sys/class/net/*/operstate")
	for _, path := range paths {
		name := filepath.Base(filepath.Dir(path))
		if strings.HasPrefix(name, "rmnet") || strings.HasPrefix(name, "ccmni") || strings.HasPrefix(name, "pdp") {
			if interfaceUp(name) {
				return "cellular"
			}
		}
	}
	return "unknown"
}

func interfaceUp(name string) bool {
	raw, err := os.ReadFile(filepath.Join("/sys/class/net", name, "operstate"))
	return err == nil && strings.TrimSpace(string(raw)) == "up"
}

func readIntFile(path string) (int, bool) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return 0, false
	}
	value, err := strconv.Atoi(strings.TrimSpace(string(raw)))
	return value, err == nil
}

func truncate(value string, maximum int) string {
	runes := []rune(value)
	if len(runes) <= maximum {
		return value
	}
	return string(runes[:maximum])
}

func writeJSONAtomic(path string, value any, mode os.FileMode) {
	raw, err := json.Marshal(value)
	if err != nil {
		return
	}
	temporary := path + ".tmp"
	if os.WriteFile(temporary, raw, mode) == nil {
		_ = os.Rename(temporary, path)
	}
}
