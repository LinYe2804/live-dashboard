package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestAppendUniqueHealth(t *testing.T) {
	record := HealthRecord{Type: "steps", Value: 1234, Unit: "steps", TimestampMS: 1000}
	records := appendUniqueHealth(nil, record)
	records = appendUniqueHealth(records, record)
	if len(records) != 1 {
		t.Fatalf("duplicate record was not removed: %d", len(records))
	}
}

func TestTruncateUnicode(t *testing.T) {
	if got := truncate("猫猫禁止视奸", 4); got != "猫猫禁止" {
		t.Fatalf("unexpected unicode truncation: %q", got)
	}
}

func TestHealthRecordValidation(t *testing.T) {
	valid := HealthRecord{Type: "heart_rate", Value: 72, Unit: "bpm", TimestampMS: 1000}
	if !validHealthRecord(valid) {
		t.Fatal("valid health record rejected")
	}
	valid.TimestampMS = 0
	if validHealthRecord(valid) {
		t.Fatal("invalid health record accepted")
	}
}

func TestMatchCustomRuleIgnoresPackageCase(t *testing.T) {
	rules := []CustomRule{{
		PackageName:   "com.JMComic3.app",
		CustomAppName: "禁漫天堂",
	}}
	rule, ok := matchCustomRule(rules, "com.jmcomic3.app")
	if !ok || rule.CustomAppName != "禁漫天堂" {
		t.Fatalf("case-insensitive rule did not match: %#v, %t", rule, ok)
	}
}

func TestPostReportUsesCustomNameAsWindowTitle(t *testing.T) {
	var payload map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Fatal(err)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	d := &daemon{httpClient: server.Client()}
	cfg := Config{
		ServerURL: server.URL,
		Token:     "test-token",
		CustomRules: []CustomRule{{
			PackageName:   "com.JMComic3.app",
			CustomAppName: "禁漫天堂",
		}},
	}
	event := Event{
		PackageName: "com.jmcomic3.app",
		AppName:     "com.jmcomic3.app",
		TimestampMS: time.Now().UnixMilli(),
	}
	if err := d.postReport(cfg, event, Event{}); err != nil {
		t.Fatal(err)
	}
	if got := payload["window_title"]; got != "禁漫天堂" {
		t.Fatalf("custom name was not used as window title: %#v", got)
	}
}
