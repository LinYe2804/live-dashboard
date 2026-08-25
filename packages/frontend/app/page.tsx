"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type FormEvent,
} from "react";
import { useDashboard } from "@/hooks/useDashboard";
import { useConfig, useConfigLoader, ConfigContext } from "@/hooks/useConfig";
import type {
  AdminDeviceConfig,
  AdminSiteConfig,
  CurrentResponse,
  DashboardProfile,
  DeviceState,
  SiteConfig,
} from "@/lib/api";
import {
  createDashboard,
  fetchAdminConfig,
  fetchConfig,
  fetchCurrent,
  fetchHealthData,
  removeAdminDeviceConfig,
  removeDashboard,
  updateAdminSiteConfig,
  updateShieldState,
  upsertAdminDeviceConfig,
  verifyAdminToken,
} from "@/lib/api";
import Header from "@/components/Header";
import CurrentStatus from "@/components/CurrentStatus";
import DeviceCard from "@/components/DeviceCard";
import DatePicker from "@/components/DatePicker";
import Timeline from "@/components/Timeline";
import HealthData from "@/components/HealthData";
import SiteMetadataSync from "@/components/SiteMetadataSync";

const SNAPSHOT_POLL_INTERVAL = 20_000;
const ADMIN_PANEL_ENABLED = process.env.NEXT_PUBLIC_ENABLE_ADMIN_PANEL === "true";

interface DashboardOption extends DashboardProfile {
  isPrimary: boolean;
}

interface DashboardSnapshot extends DashboardOption {
  onlineDevices: number;
  totalDevices: number;
  viewerCount: number;
  activeLabel: string;
  statusText: string;
  reachable: boolean;
}

export default function Home() {
  const config = useConfigLoader();
  const [runtimeConfig, setRuntimeConfig] = useState<SiteConfig>(config);

  useEffect(() => {
    setRuntimeConfig(config);
  }, [config]);

  const handleSiteConfigUpdated = useCallback((site: AdminSiteConfig) => {
    setRuntimeConfig((prev) => ({
      ...prev,
      displayName: site.displayName,
      siteTitle: site.siteTitle,
      siteDescription: site.siteDescription,
      shieldEnabled: site.shieldEnabled,
      shieldStatusText: site.shieldStatusText,
      backgroundImage: site.backgroundImage,
      backgroundBlur: site.backgroundBlur,
      backgroundOpacity: site.backgroundOpacity,
      glassOpacity: site.glassOpacity,
    }));
  }, []);

  const handleShieldUpdated = useCallback((shieldEnabled: boolean, shieldStatusText?: string) => {
    setRuntimeConfig((prev) => ({
      ...prev,
      shieldEnabled,
      shieldStatusText: shieldStatusText ?? prev.shieldStatusText,
    }));
  }, []);

  return (
    <ConfigContext.Provider value={runtimeConfig}>
      <SiteMetadataSync />
      <HomeInner
        onSiteConfigUpdated={handleSiteConfigUpdated}
        onShieldUpdated={handleShieldUpdated}
      />
    </ConfigContext.Provider>
  );
}

function HomeInner({
  onSiteConfigUpdated,
  onShieldUpdated,
}: {
  onSiteConfigUpdated: (site: AdminSiteConfig) => void;
  onShieldUpdated: (enabled: boolean, statusText?: string) => void;
}) {
  const config = useConfig();
  const { displayName } = config;
  const [runtimeDashboards, setRuntimeDashboards] = useState<DashboardProfile[]>(config.dashboards);
  const [adminToken, setAdminToken] = useState("");
  const [adminDevices, setAdminDevices] = useState<AdminDeviceConfig[]>([]);
  const [adminStatus, setAdminStatus] = useState<string | null>(null);

  useEffect(() => {
    setRuntimeDashboards(config.dashboards);
  }, [config.dashboards]);

  const handleAdminTokenChange = useCallback((value: string) => {
    setAdminToken(value);
  }, []);

  const loadAdminConfig = useCallback(async (token: string) => {
    const adminConfig = await fetchAdminConfig(token);
    setAdminDevices(adminConfig.devices);
    onSiteConfigUpdated(adminConfig.site);
  }, [onSiteConfigUpdated]);

  const handleAdminUnlock = useCallback(async (token: string) => {
    const normalized = token.trim();
    await loadAdminConfig(normalized);
    setAdminToken(normalized);
  }, [loadAdminConfig]);

  const handleAdminLock = useCallback(() => {
    setAdminToken("");
    setAdminDevices([]);
  }, []);

  const refreshDashboardConfig = useCallback(async () => {
    const latest = await fetchConfig();
    setRuntimeDashboards(latest.dashboards);
  }, []);

  const handleDashboardCreate = useCallback(async (payload: DashboardProfile) => {
    if (!adminToken.trim()) {
      setAdminStatus("请先填写管理密码");
      return;
    }

    try {
      setAdminStatus("正在保存面板...");
      const dashboards = await createDashboard(payload, adminToken.trim());
      setRuntimeDashboards(dashboards);
      setAdminStatus("面板已保存（立即生效，无需改 .env / 重建）");
    } catch (error) {
      const message = error instanceof Error ? error.message : "请检查 Token 和面板地址";
      setAdminStatus(`保存失败：${message}`);
    }
  }, [adminToken]);

  const handleDashboardDelete = useCallback(async (id: string) => {
    if (!adminToken.trim()) {
      setAdminStatus("请先填写管理密码");
      return;
    }

    try {
      setAdminStatus("正在删除面板...");
      const dashboards = await removeDashboard(id, adminToken.trim());
      setRuntimeDashboards(dashboards);
      setAdminStatus("面板已删除（立即生效，无需改 .env / 重建）");
    } catch (error) {
      const message = error instanceof Error ? error.message : "请检查 Token";
      setAdminStatus(`删除失败：${message}`);
    }
  }, [adminToken]);

  const handleDashboardReload = useCallback(async () => {
    try {
      await refreshDashboardConfig();
      if (adminToken.trim()) {
        await loadAdminConfig(adminToken.trim());
      }
      setAdminStatus("配置已刷新");
    } catch {
      setAdminStatus("刷新失败");
    }
  }, [adminToken, loadAdminConfig, refreshDashboardConfig]);

  const handleSiteSave = useCallback(async (payload: AdminSiteConfig) => {
    if (!adminToken.trim()) {
      setAdminStatus("请先填写管理密码");
      return;
    }

    try {
      setAdminStatus("正在保存网页外观...");
      const site = await updateAdminSiteConfig(payload, adminToken.trim());
      onSiteConfigUpdated(site);
      setAdminStatus("网页外观已更新");
    } catch (error) {
      const message = error instanceof Error ? error.message : "请检查 Token";
      setAdminStatus(`网页外观保存失败：${message}`);
    }
  }, [adminToken, onSiteConfigUpdated]);

  const handleDeviceSave = useCallback(async (payload: {
    token: string;
    device_id: string;
    device_name: string;
    platform: "windows" | "android" | "macos";
  }) => {
    if (!adminToken.trim()) {
      setAdminStatus("请先填写管理密码");
      return;
    }

    try {
      setAdminStatus("正在保存设备配置...");
      const devices = await upsertAdminDeviceConfig(payload, adminToken.trim());
      setAdminDevices(devices);
      setAdminStatus("主面板设备已添加/更新（未上报时会显示离线）");
    } catch (error) {
      const message = error instanceof Error ? error.message : "请检查 Token";
      setAdminStatus(`设备配置保存失败：${message}`);
    }
  }, [adminToken]);

  const handleDeviceDelete = useCallback(async (deviceId: string) => {
    if (!adminToken.trim()) {
      setAdminStatus("请先填写管理密码");
      return;
    }

    try {
      setAdminStatus("正在删除运行时设备覆盖...");
      const devices = await removeAdminDeviceConfig(deviceId, adminToken.trim());
      setAdminDevices(devices);
      setAdminStatus("主面板设备覆盖已删除");
    } catch (error) {
      const message = error instanceof Error ? error.message : "请检查 Token";
      setAdminStatus(`删除失败：${message}`);
    }
  }, [adminToken]);

  const handleShieldChange = useCallback(async (enabled: boolean, statusText?: string) => {
    if (!adminToken.trim()) {
      setAdminStatus("请先填写管理密码");
      return;
    }

    try {
      setAdminStatus(enabled ? "正在开启屏蔽状态..." : "正在关闭屏蔽状态...");
      const settings = await updateShieldState(enabled, adminToken.trim(), statusText);
      onShieldUpdated(settings.shieldEnabled, settings.shieldStatusText);
      setAdminStatus(settings.shieldEnabled ? "屏蔽状态已开启，访客数据已替换为乱码" : "屏蔽状态已关闭");
    } catch (error) {
      const message = error instanceof Error ? error.message : "请检查管理密码";
      setAdminStatus(`屏蔽状态更新失败：${message}`);
    }
  }, [adminToken, onShieldUpdated]);

  const handleShieldClose = useCallback(async (password: string) => {
    const normalized = password.trim();
    const settings = await updateShieldState(false, normalized);
    onShieldUpdated(settings.shieldEnabled, settings.shieldStatusText);
  }, [onShieldUpdated]);

  const dashboards = useMemo<DashboardOption[]>(() => {
    return [
      {
        id: "local",
        name: displayName,
        url: "",
        description: `${displayName} 的主面板`,
        isPrimary: true,
      },
      ...runtimeDashboards.map((dashboard) => ({
        ...dashboard,
        isPrimary: false,
      })),
    ];
  }, [runtimeDashboards, displayName]);

  const [selectedDashboardId, setSelectedDashboardId] = useState("local");
  const [dashboardSnapshots, setDashboardSnapshots] = useState<Record<string, DashboardSnapshot>>({});
  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null);
  const [tab, setTab] = useState<"activity" | "health">("activity");
  const [hasHealthData, setHasHealthData] = useState(false);
  const snapshotRequestIdRef = useRef(0);

  useEffect(() => {
    if (!dashboards.some((dashboard) => dashboard.id === selectedDashboardId)) {
      setSelectedDashboardId("local");
    }
  }, [dashboards, selectedDashboardId]);

  const activeDashboard = useMemo(() => {
    return dashboards.find((dashboard) => dashboard.id === selectedDashboardId) ?? dashboards[0];
  }, [dashboards, selectedDashboardId]);
  const activeDashboardId = activeDashboard?.isPrimary ? undefined : activeDashboard?.id;
  const {
    current,
    timeline,
    selectedDate,
    changeDate,
    loading,
    refreshing,
    timelineLoading,
    timelineRefreshing,
    error,
    viewerCount,
  } = useDashboard(activeDashboardId, adminToken);
  const snapshotTargets = useMemo(() => {
    const activeId = activeDashboard?.id;
    return dashboards.filter((dashboard) => dashboard.id !== activeId);
  }, [activeDashboard?.id, dashboards]);

  useEffect(() => {
    setSelectedDeviceId(null);
    setTab("activity");
  }, [selectedDashboardId]);

  useEffect(() => {
    if (!activeDashboard || !current) return;

    const nextSnapshot = buildDashboardSnapshot(activeDashboard, current);
    setDashboardSnapshots((prev) => ({
      ...prev,
      [activeDashboard.id]: nextSnapshot,
    }));
  }, [activeDashboard, current]);

  useEffect(() => {
    let disposed = false;

    const loadSnapshots = () => {
      const requestId = ++snapshotRequestIdRef.current;

      for (const dashboard of snapshotTargets) {
        void fetchCurrent(
          undefined,
          dashboard.isPrimary
            ? (adminToken ? { adminToken } : undefined)
            : { dashboardId: dashboard.id, adminToken },
        )
          .then((response) => {
            if (disposed || requestId !== snapshotRequestIdRef.current) return;
            const nextSnapshot = buildDashboardSnapshot(dashboard, response);
            setDashboardSnapshots((prev) => ({
              ...prev,
              [dashboard.id]: nextSnapshot,
            }));
          })
          .catch(() => {
            if (disposed || requestId !== snapshotRequestIdRef.current) return;
            const nextSnapshot = buildDashboardSnapshot(dashboard, null);
            setDashboardSnapshots((prev) => ({
              ...prev,
              [dashboard.id]: nextSnapshot,
            }));
          });
      }
    };

    loadSnapshots();
    const timer = window.setInterval(loadSnapshots, SNAPSHOT_POLL_INTERVAL);
    return () => {
      disposed = true;
      window.clearInterval(timer);
    };
  }, [adminToken, snapshotTargets]);

  useEffect(() => {
    if (!hasHealthData && tab === "health") setTab("activity");
  }, [hasHealthData, tab]);

  const currentAppByDevice = useMemo(() => {
    const map: Record<string, string> = {};
    if (current?.devices) {
      for (const device of current.devices) {
        if (device.is_online === 1 && device.app_name) {
          map[device.device_id] = device.app_name;
        }
      }
    }
    return map;
  }, [current?.devices]);

  const allOffline = useMemo(() => {
    if (!current?.devices || current.devices.length === 0) return false;
    return current.devices.every((device) => device.is_online !== 1);
  }, [current?.devices]);

  const devices = useMemo(() => {
    const list = current?.devices ?? [];
    return [...list].sort((left, right) => left.device_id.localeCompare(right.device_id));
  }, [current?.devices]);

  const selectedDevice = useMemo(() => {
    if (devices.length === 0) return undefined;
    if (selectedDeviceId) {
      const found = devices.find((device) => device.device_id === selectedDeviceId);
      if (found) return found;
    }
    return devices.find((device) => device.is_online === 1) || devices[0];
  }, [devices, selectedDeviceId]);

  const selectedDeviceIdResolved = selectedDevice?.device_id;

  useEffect(() => {
    if (!selectedDate || !selectedDeviceIdResolved) {
      setHasHealthData(false);
      return;
    }

    const controller = new AbortController();
    let requestInFlight = false;
    setHasHealthData(false);

    const probeHealthData = async () => {
      if (requestInFlight || controller.signal.aborted) return;
      requestInFlight = true;
      try {
        const result = await fetchHealthData(
          selectedDate,
          controller.signal,
          selectedDeviceIdResolved,
          activeDashboardId || adminToken ? { dashboardId: activeDashboardId, adminToken } : undefined,
        );
        if (!controller.signal.aborted) setHasHealthData(result.records.length > 0);
      } catch {
        // Keep the last known availability during a transient network failure.
      } finally {
        requestInFlight = false;
      }
    };

    const refreshWhenVisible = () => {
      if (document.visibilityState === "visible") void probeHealthData();
    };

    void probeHealthData();
    const timer = window.setInterval(probeHealthData, 15_000);
    window.addEventListener("focus", refreshWhenVisible);
    document.addEventListener("visibilitychange", refreshWhenVisible);

    return () => {
      controller.abort();
      window.clearInterval(timer);
      window.removeEventListener("focus", refreshWhenVisible);
      document.removeEventListener("visibilitychange", refreshWhenVisible);
    };
  }, [activeDashboardId, adminToken, selectedDate, selectedDeviceIdResolved]);

  const filteredTimeline = useMemo(() => {
    if (!timeline || !selectedDevice) return timeline;
    const deviceId = selectedDevice.device_id;
    const segments = timeline.segments ?? [];
    const summary = timeline.summary ?? {};
    return {
      ...timeline,
      segments: segments.filter((segment) => segment.device_id === deviceId),
      summary: deviceId in summary ? { [deviceId]: summary[deviceId] } : {},
    };
  }, [timeline, selectedDevice]);

  const resolvedSnapshots = useMemo(() => {
    return dashboards.map((dashboard) => {
      return dashboardSnapshots[dashboard.id] ?? buildDashboardSnapshot(dashboard, null);
    });
  }, [dashboardSnapshots, dashboards]);

  useEffect(() => {
    document.body.classList.toggle("night-mode", allOffline);
    return () => {
      document.body.classList.remove("night-mode");
    };
  }, [allOffline]);

  const shieldVisible = !adminToken && config.shieldEnabled;
  const backgroundStyle = {
    backgroundImage: config.backgroundImage
      ? `url(${JSON.stringify(config.backgroundImage)})`
      : undefined,
    "--background-blur": `${config.backgroundBlur}px`,
    opacity: config.backgroundImage ? config.backgroundOpacity / 100 : 0,
  } as CSSProperties;
  const dashboardStyle = {
    "--glass-opacity": `${config.glassOpacity}%`,
  } as CSSProperties;

  return (
    <>
      <div className="dashboard-background" style={backgroundStyle} aria-hidden="true" />
      <div
        className={`dashboard-ambient${config.backgroundImage ? " dashboard-ambient-custom" : ""}`}
        aria-hidden="true"
      >
        <span className="ambient-orb ambient-orb-one" />
        <span className="ambient-orb ambient-orb-two" />
        <span className="ambient-orb ambient-orb-three" />
      </div>
      <div
        className={`dashboard-shell${shieldVisible ? " shielded-dashboard-content" : ""}`}
        style={dashboardStyle}
      >
      <LoadingRail
        active={loading || refreshing || timelineLoading || timelineRefreshing}
        label={loading ? "正在连接实时状态" : timelineLoading ? "正在加载时间线" : "正在同步最新状态"}
      />
      <Header
        serverTime={current?.server_time}
        viewerCount={viewerCount}
        displayName={activeDashboard?.name ?? displayName}
      />

      {ADMIN_PANEL_ENABLED && (
        <DashboardAdminPanel
          dashboards={dashboards.filter((item) => !item.isPrimary)}
          siteConfig={{
            displayName: config.displayName,
            siteTitle: config.siteTitle,
            siteDescription: config.siteDescription,
            shieldEnabled: config.shieldEnabled,
            shieldStatusText: config.shieldStatusText,
            backgroundImage: config.backgroundImage,
            backgroundBlur: config.backgroundBlur,
            backgroundOpacity: config.backgroundOpacity,
            glassOpacity: config.glassOpacity,
          }}
          devices={adminDevices}
          adminToken={adminToken}
          adminStatus={adminStatus}
          onAdminTokenChange={handleAdminTokenChange}
          onAdminUnlock={handleAdminUnlock}
          onAdminLock={handleAdminLock}
          onCreate={handleDashboardCreate}
          onDelete={handleDashboardDelete}
          onReload={handleDashboardReload}
          onSaveSite={handleSiteSave}
          onSaveDevice={handleDeviceSave}
          onDeleteDevice={handleDeviceDelete}
          shieldEnabled={config.shieldEnabled}
          onShieldChange={handleShieldChange}
        />
      )}

      <DashboardSwitcher
        dashboards={resolvedSnapshots}
        selectedDashboardId={activeDashboard?.id ?? "local"}
        onSelect={setSelectedDashboardId}
      />

      {error && (
        <div className="vn-bubble mb-4 border-[var(--color-primary)]">
          <p className="text-sm text-[var(--color-primary)]">
            (&gt;_&lt;) {activeDashboard?.name ?? displayName} 的面板连接失败了喵...
          </p>
          <p className="text-xs text-[var(--color-text-muted)] mt-1">
            别担心，会自动重试的~
          </p>
        </div>
      )}

      {loading && !current ? (
        <DashboardLoadingSkeleton />
      ) : (
        <section className="overview-grid content-reveal mb-6 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          {resolvedSnapshots.map((dashboard) => (
            <DashboardOverviewCard
              key={dashboard.id}
              dashboard={dashboard}
              selected={dashboard.id === activeDashboard?.id}
              onSelect={() => setSelectedDashboardId(dashboard.id)}
            />
          ))}
        </section>
      )}

      {current && (
        <>
          <div className="content-reveal">
            <CurrentStatus device={selectedDevice} displayName={activeDashboard?.name} />
          </div>

          <div className="dashboard-workspace glass-panel flex flex-col lg:flex-row gap-6 rounded-3xl p-4 md:p-5">
            <div className="lg:w-56 flex-shrink-0 space-y-2">
              <h2 className="text-xs font-bold text-[var(--color-text-muted)] uppercase tracking-wider mb-2">
                Devices
              </h2>
              {devices.length === 0 ? (
                <div className="text-center py-4">
                  <p className="text-lg mb-1">( -ω-) zzZ</p>
                  <p className="text-xs text-[var(--color-text-muted)] italic">
                    还没有设备连接呢~
                  </p>
                </div>
              ) : (
                devices.map((device) => (
                  <DeviceCard
                    key={device.device_id}
                    device={device}
                    selected={selectedDevice?.device_id === device.device_id}
                    onSelect={() => setSelectedDeviceId(device.device_id)}
                  />
                ))
              )}
            </div>

            <div className="flex-1 min-w-0">
              <div className="flex items-center justify-between mb-3 flex-wrap gap-2">
                <DatePicker selectedDate={selectedDate} onChange={changeDate} />
                {hasHealthData && (
                  <div className="flex gap-1">
                    <button
                      type="button"
                      onClick={() => setTab("activity")}
                      className={`pill-btn text-xs px-3 py-1 ${
                        tab === "activity"
                          ? "bg-[var(--color-primary)] text-white border-[var(--color-primary)]"
                          : ""
                      }`}
                    >
                      活动
                    </button>
                    <button
                      type="button"
                      onClick={() => setTab("health")}
                      className={`pill-btn text-xs px-3 py-1 ${
                        tab === "health"
                          ? "bg-[var(--color-primary)] text-white border-[var(--color-primary)]"
                          : ""
                      }`}
                    >
                      健康
                    </button>
                  </div>
                )}
              </div>

              <div className="separator-dashed mb-3" />

              {devices.length > 1 && <DeviceOverview devices={devices} />}

              {tab === "activity" ? (
                <>
                  {timelineRefreshing && filteredTimeline ? (
                    <div className="content-refreshing">
                      <Timeline
                        segments={filteredTimeline.segments}
                        summary={filteredTimeline.summary}
                        currentAppByDevice={currentAppByDevice}
                      />
                    </div>
                  ) : filteredTimeline ? (
                    <Timeline
                      segments={filteredTimeline.segments}
                      summary={filteredTimeline.summary}
                      currentAppByDevice={currentAppByDevice}
                    />
                  ) : timelineLoading ? (
                    <TimelineLoadingSkeleton />
                  ) : null}
                </>
              ) : (
                <HealthData
                  selectedDate={selectedDate}
                  deviceId={selectedDevice?.device_id}
                  dashboardId={activeDashboardId}
                  adminToken={adminToken}
                />
              )}
            </div>
          </div>
        </>
      )}

      <footer className="mt-12 pt-4 separator-dashed text-center">
        <p className="text-[10px] text-[var(--color-text-muted)]">
          {displayName} Now &middot; 已接入 {resolvedSnapshots.length} 个面板 &middot; 状态 10 秒刷新 / 时间线 30 秒刷新 &middot; (◕ᴗ◕)
        </p>
      </footer>
      </div>

      {shieldVisible && (
        <ShieldOverlay
          statusText={config.shieldStatusText}
          onCloseShield={handleShieldClose}
        />
      )}
    </>
  );
}

function ShieldOverlay({
  statusText,
  onCloseShield,
}: {
  statusText: string;
  onCloseShield: (password: string) => Promise<void>;
}) {
  const [showPassword, setShowPassword] = useState(false);
  const [password, setPassword] = useState("");
  const [closing, setClosing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    document.body.classList.add("shield-overlay-open");
    return () => document.body.classList.remove("shield-overlay-open");
  }, []);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!password.trim()) {
      setError("请输入管理员密码");
      return;
    }

    setClosing(true);
    setError(null);
    try {
      await onCloseShield(password);
    } catch (submitError) {
      const message = submitError instanceof Error ? submitError.message : "验证失败";
      setError(message === "Unauthorized" ? "管理员密码不正确" : `关闭失败：${message}`);
    } finally {
      setClosing(false);
    }
  };

  return (
    <div className="shield-overlay" role="dialog" aria-modal="true" aria-labelledby="shield-title">
      <div className="shield-noise" aria-hidden="true" />
      <div className="shield-orbit shield-orbit-one" aria-hidden="true">✦　✧　✦</div>
      <div className="shield-orbit shield-orbit-two" aria-hidden="true">×　×　×</div>

      <div className="shield-warning-card">
        <div className="shield-glossy-x" aria-hidden="true">×</div>
        <p className="shield-kicker">PRIVACY PAWTOCOL / 403</p>
        <h1 id="shield-title">目前已开启屏蔽状态</h1>
        <p className="shield-message">猫猫不允许视奸</p>
        <div className="shield-redacted" aria-label="状态数据已隐藏">
          <span>STATUS</span>
          <b>{statusText}</b>
        </div>
        <p className="shield-footnote">实时状态已被加密 · 请尊重边界喵</p>
      </div>

      {showPassword && (
        <form className="shield-password-card" onSubmit={handleSubmit}>
          <label htmlFor="shield-admin-password">输入管理员密码以关闭屏蔽</label>
          <div className="shield-password-row">
            <input
              id="shield-admin-password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoFocus
              autoComplete="current-password"
              placeholder="管理员密码"
              disabled={closing}
            />
            <button type="submit" disabled={closing}>
              {closing ? "验证中…" : "确认关闭"}
            </button>
          </div>
          {error && <p className="shield-password-error">{error}</p>}
        </form>
      )}

      <button
        type="button"
        className="shield-close-button"
        onClick={() => {
          setShowPassword((value) => !value);
          setError(null);
        }}
      >
        {showPassword ? "取消" : "关闭"}
      </button>
    </div>
  );
}

function buildDashboardSnapshot(
  dashboard: DashboardOption,
  current: CurrentResponse | null,
): DashboardSnapshot {
  if (!current) {
    return {
      ...dashboard,
      onlineDevices: 0,
      totalDevices: 0,
      viewerCount: 0,
      activeLabel: "暂时无法访问",
      statusText: "连接失败",
      reachable: false,
    };
  }

  const onlineDevices = current.devices.filter((device) => device.is_online === 1);
  const activeDevice = onlineDevices[0] ?? current.devices[0];
  const activeLabel = activeDevice
    ? activeDevice.is_online === 1
      ? activeDevice.app_name === "idle"
        ? "暂时离开"
        : activeDevice.app_name || "在线"
      : "当前离线"
    : "暂无设备";

  return {
    ...dashboard,
    onlineDevices: onlineDevices.length,
    totalDevices: current.devices.length,
    viewerCount: current.viewer_count ?? 0,
    activeLabel,
    statusText: onlineDevices.length > 0 ? "在线" : current.devices.length > 0 ? "离线" : "暂无设备",
    reachable: true,
  };
}

function LoadingRail({ active, label }: { active: boolean; label: string }) {
  return (
    <div
      className={`loading-rail${active ? " loading-rail-active" : ""}`}
      role="status"
      aria-live="polite"
      aria-label={active ? label : undefined}
    >
      <span className="loading-rail-track" aria-hidden="true">
        <span className="loading-rail-glow" />
      </span>
      <span className="loading-rail-label">{label}</span>
    </div>
  );
}

function DashboardLoadingSkeleton() {
  return (
    <section className="dashboard-loading" aria-label="正在加载实时面板" aria-busy="true">
      <div className="loading-welcome glass-panel">
        <div className="loading-emblem" aria-hidden="true">
          <span className="loading-emblem-ring" />
          <span className="loading-emblem-spark loading-emblem-spark-one" />
          <span className="loading-emblem-spark loading-emblem-spark-two" />
        </div>
        <div>
          <p className="loading-welcome-title">正在连接实时世界</p>
          <p className="loading-welcome-copy">猫猫正在轻轻整理状态数据...</p>
        </div>
        <div className="loading-wave" aria-hidden="true">
          {Array.from({ length: 7 }, (_, index) => <span key={index} />)}
        </div>
      </div>

      <div className="loading-overview-grid">
        {Array.from({ length: 4 }, (_, index) => (
          <div key={index} className="loading-card glass-panel" style={{ animationDelay: `${index * 70}ms` }}>
            <div className="skeleton-line skeleton-line-title" />
            <div className="skeleton-pill" />
            <div className="skeleton-line skeleton-line-wide" />
            <div className="loading-card-stats">
              <span /><span /><span />
            </div>
          </div>
        ))}
      </div>

      <div className="loading-status-card glass-panel">
        <div className="skeleton-line skeleton-line-short" />
        <div className="skeleton-line skeleton-line-focus" />
        <div className="skeleton-line skeleton-line-medium" />
      </div>

      <div className="loading-workspace glass-panel">
        <div className="loading-device-column">
          <div className="skeleton-line skeleton-line-short" />
          {Array.from({ length: 3 }, (_, index) => <div key={index} className="loading-device-row" />)}
        </div>
        <TimelineLoadingSkeleton />
      </div>
    </section>
  );
}

function TimelineLoadingSkeleton() {
  return (
    <div className="timeline-loading" aria-label="正在加载时间线" aria-busy="true">
      <div className="skeleton-line skeleton-line-short" />
      {Array.from({ length: 5 }, (_, index) => (
        <div key={index} className="timeline-loading-row" style={{ animationDelay: `${index * 85}ms` }}>
          <span className="timeline-loading-dot" />
          <span className="skeleton-line" />
          <span className="timeline-loading-time" />
        </div>
      ))}
    </div>
  );
}

function DashboardSwitcher({
  dashboards,
  selectedDashboardId,
  onSelect,
}: {
  dashboards: DashboardSnapshot[];
  selectedDashboardId: string;
  onSelect: (id: string) => void;
}) {
  return (
    <section className="switcher-panel glass-panel mb-4 rounded-2xl p-4">
      <div className="mb-2">
        <p className="text-xs font-bold uppercase tracking-[0.25em] text-[var(--color-text-muted)]">
          Panels
        </p>
        <p className="text-xs text-[var(--color-text-muted)] mt-1">
          点击切换完整时间线，下方卡片可以同时看所有人的在线状态。
        </p>
      </div>

      <div className="flex flex-wrap gap-2">
        {dashboards.map((dashboard) => (
          <button
            key={dashboard.id}
            type="button"
            onClick={() => onSelect(dashboard.id)}
            className={`panel-chip ${dashboard.id === selectedDashboardId ? "panel-chip-active" : ""}`}
          >
            <span>{dashboard.name}</span>
            <span className="text-[10px] opacity-70">{dashboard.onlineDevices}/{dashboard.totalDevices}</span>
          </button>
        ))}
      </div>
    </section>
  );
}

function DashboardAdminPanel({
  dashboards,
  siteConfig,
  devices,
  adminToken,
  adminStatus,
  onAdminTokenChange,
  onAdminUnlock,
  onAdminLock,
  onCreate,
  onDelete,
  onSaveSite,
  onSaveDevice,
  onDeleteDevice,
  onReload,
  shieldEnabled,
  onShieldChange,
}: {
  dashboards: DashboardProfile[];
  siteConfig: AdminSiteConfig;
  devices: AdminDeviceConfig[];
  adminToken: string;
  adminStatus: string | null;
  onAdminTokenChange: (token: string) => void;
  onAdminUnlock: (token: string) => Promise<void>;
  onAdminLock: () => void;
  onCreate: (payload: DashboardProfile) => void;
  onDelete: (id: string) => void;
  onSaveSite: (payload: AdminSiteConfig) => void;
  onSaveDevice: (payload: {
    token: string;
    device_id: string;
    device_name: string;
    platform: "windows" | "android" | "macos";
  }) => void;
  onDeleteDevice: (deviceId: string) => void;
  onReload: () => void;
  shieldEnabled: boolean;
  onShieldChange: (enabled: boolean, statusText?: string) => void;
}) {
  const [expanded, setExpanded] = useState(true);
  const [unlocked, setUnlocked] = useState(false);
  const [passwordInput, setPasswordInput] = useState("");
  const [unlocking, setUnlocking] = useState(false);
  const [unlockStatus, setUnlockStatus] = useState<string | null>(null);
  const [id, setId] = useState("");
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [description, setDescription] = useState("");
  const [siteDisplayName, setSiteDisplayName] = useState(siteConfig.displayName);
  const [siteTitle, setSiteTitle] = useState(siteConfig.siteTitle);
  const [siteDescription, setSiteDescription] = useState(siteConfig.siteDescription);
  const [backgroundImage, setBackgroundImage] = useState(siteConfig.backgroundImage);
  const [backgroundBlur, setBackgroundBlur] = useState(siteConfig.backgroundBlur);
  const [backgroundOpacity, setBackgroundOpacity] = useState(siteConfig.backgroundOpacity);
  const [glassOpacity, setGlassOpacity] = useState(siteConfig.glassOpacity);
  const [deviceToken, setDeviceToken] = useState("");
  const [deviceId, setDeviceId] = useState("");
  const [deviceName, setDeviceName] = useState("");
  const [devicePlatform, setDevicePlatform] = useState<"windows" | "android" | "macos">("windows");
  const [shieldStatusText, setShieldStatusText] = useState(siteConfig.shieldStatusText);

  useEffect(() => {
    setSiteDisplayName(siteConfig.displayName);
    setSiteTitle(siteConfig.siteTitle);
    setSiteDescription(siteConfig.siteDescription);
    setBackgroundImage(siteConfig.backgroundImage);
    setBackgroundBlur(siteConfig.backgroundBlur);
    setBackgroundOpacity(siteConfig.backgroundOpacity);
    setGlassOpacity(siteConfig.glassOpacity);
    setShieldStatusText(siteConfig.shieldStatusText);
  }, [
    siteConfig.backgroundBlur,
    siteConfig.backgroundImage,
    siteConfig.backgroundOpacity,
    siteConfig.displayName,
    siteConfig.glassOpacity,
    siteConfig.shieldStatusText,
    siteConfig.siteDescription,
    siteConfig.siteTitle,
  ]);

  const handleUnlock = async () => {
    const password = passwordInput.trim();
    if (!password) {
      setUnlockStatus("请先填写管理密码");
      return;
    }

    setUnlocking(true);
    setUnlockStatus("正在验证管理密码...");
    try {
      await verifyAdminToken(password);
      await onAdminUnlock(password);
      setUnlocked(true);
      setUnlockStatus("管理密码验证成功");
    } catch (error) {
      onAdminTokenChange("");
      const message = error instanceof Error ? error.message : "验证失败";
      setUnlockStatus(`解锁失败：${message}`);
    } finally {
      setUnlocking(false);
    }
  };

  const handleLock = () => {
    setUnlocked(false);
    setPasswordInput("");
    setUnlockStatus(null);
    onAdminTokenChange("");
    onAdminLock();
  };

  const applyDeviceToForm = (device: AdminDeviceConfig) => {
    setDeviceToken(device.token);
    setDeviceId(device.device_id);
    setDeviceName(device.device_name);
    setDevicePlatform(device.platform);
  };

  return (
    <section className="admin-panel glass-panel mb-4 rounded-2xl border-2 border-[var(--color-accent)] px-4 py-3">
      <div className="flex flex-wrap items-center justify-between gap-2 mb-2">
        <div>
          <p className="text-xs font-bold uppercase tracking-[0.2em] text-[var(--color-text-muted)]">
            多人面板管理
          </p>
          <p className="text-xs text-[var(--color-text-muted)] mt-1">
            网页直接管理面板、设备、屏蔽状态与视觉外观（需要管理密码）
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-[var(--color-text-muted)]">当前 {dashboards.length} 个外部面板</span>
          <button
            type="button"
            onClick={() => setExpanded((value) => !value)}
            className="pill-btn text-xs px-3 py-1"
          >
            {expanded ? "收起" : "展开"}
          </button>
          <button type="button" onClick={onReload} className="pill-btn text-xs px-3 py-1">刷新列表</button>
        </div>
      </div>

      {expanded && (
        <>
          {!unlocked ? (
            <>
              <div className="grid gap-2 md:grid-cols-[1fr_auto]">
                <input
                  type="password"
                  value={passwordInput}
                  onChange={(event) => setPasswordInput(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" && !unlocking) {
                      event.preventDefault();
                      void handleUnlock();
                    }
                  }}
                  placeholder="先输入管理密码（ADMIN_PASSWORD / ADMIN_TOKEN）"
                  autoComplete="new-password"
                  className="panel-chip w-full text-xs px-3 py-2"
                />
                <button
                  type="button"
                  onClick={() => {
                    void handleUnlock();
                  }}
                  disabled={unlocking}
                  className="pill-btn text-xs px-3 py-1"
                >
                  {unlocking ? "验证中..." : "解锁管理"}
                </button>
              </div>
              <p className="text-xs text-[var(--color-text-muted)] mt-2">
                解锁后才会显示添加/更新/删除按钮。
              </p>
              {unlockStatus && (
                <p className="text-xs text-[var(--color-text-muted)] mt-2">{unlockStatus}</p>
              )}
            </>
          ) : (
            <>
              <div className="flex items-center gap-2 mb-2">
                <span className="text-xs text-[var(--color-text-muted)]">已解锁，可管理面板</span>
                <button
                  type="button"
                  onClick={handleLock}
                  className="pill-btn text-xs px-3 py-1"
                >
                  锁定
                </button>
              </div>

              <div className="shield-admin-control mb-3">
                <div>
                  <p className="text-sm font-bold text-[var(--color-text)]">访客屏蔽状态</p>
                  <p className="text-xs text-[var(--color-text-muted)] mt-1">
                    开启后访客只会看到猫猫警告，实时数据由服务器替换为乱码。
                  </p>
                </div>
                <label className="shield-switch">
                  <input
                    type="checkbox"
                    checked={shieldEnabled}
                    onChange={(event) => onShieldChange(event.target.checked, shieldStatusText)}
                    aria-label="切换访客屏蔽状态"
                  />
                  <span className="shield-switch-track" aria-hidden="true">
                    <span className="shield-switch-thumb">{shieldEnabled ? "×" : null}</span>
                  </span>
                  <span className="text-xs font-bold">
                    {shieldEnabled ? "已屏蔽" : "未屏蔽"}
                  </span>
                </label>
                <div className="shield-status-editor">
                  <input
                    value={shieldStatusText}
                    onChange={(event) => setShieldStatusText(event.target.value)}
                    maxLength={160}
                    placeholder="自定义 STATUS 内容"
                    aria-label="自定义屏蔽状态内容"
                  />
                  <button
                    type="button"
                    onClick={() => onShieldChange(shieldEnabled, shieldStatusText)}
                  >
                    保存内容
                  </button>
                </div>
              </div>

              <div className="grid gap-2 md:grid-cols-2">
                <input
                  value={id}
                  onChange={(event) => setId(event.target.value)}
                  placeholder="面板 ID（如: friend-1）"
                  className="panel-chip w-full text-xs px-3 py-2"
                />
                <input
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                  placeholder="显示名称"
                  className="panel-chip w-full text-xs px-3 py-2"
                />
                <input
                  value={url}
                  onChange={(event) => setUrl(event.target.value)}
                  placeholder="面板 URL（https://...）"
                  className="panel-chip w-full text-xs px-3 py-2"
                />
                <input
                  value={description}
                  onChange={(event) => setDescription(event.target.value)}
                  placeholder="描述（可选）"
                  className="panel-chip w-full text-xs px-3 py-2"
                />
              </div>

              <div className="mt-2">
                <button
                  type="button"
                  onClick={() => {
                    onCreate({
                      id: id.trim(),
                      name: name.trim(),
                      url: url.trim(),
                      description: description.trim() || undefined,
                    });
                  }}
                  className="pill-btn text-xs px-3 py-1"
                >
                  添加 / 更新面板
                </button>
              </div>

              {dashboards.length > 0 && (
                <div className="mt-3 flex flex-wrap gap-2">
                  {dashboards.map((dashboard) => (
                    <button
                      key={dashboard.id}
                      type="button"
                      onClick={() => onDelete(dashboard.id)}
                      className="panel-chip text-xs px-3 py-1"
                      title={`删除 ${dashboard.name}`}
                    >
                      删除 {dashboard.name}
                    </button>
                  ))}
                </div>
              )}

              <div className="separator-dashed my-3" />
              <p className="text-xs font-bold uppercase tracking-[0.2em] text-[var(--color-text-muted)] mb-2">
                网页名称配置
              </p>
              <div className="grid gap-2 md:grid-cols-2">
                <input
                  value={siteDisplayName}
                  onChange={(event) => setSiteDisplayName(event.target.value)}
                  placeholder="主面板显示名（displayName）"
                  className="panel-chip w-full text-xs px-3 py-2"
                />
                <input
                  value={siteTitle}
                  onChange={(event) => setSiteTitle(event.target.value)}
                  placeholder="网页标题（siteTitle）"
                  className="panel-chip w-full text-xs px-3 py-2"
                />
                <input
                  value={siteDescription}
                  onChange={(event) => setSiteDescription(event.target.value)}
                  placeholder="网页描述（siteDescription）"
                  className="panel-chip w-full text-xs px-3 py-2 md:col-span-2"
                />
              </div>

              <div className="appearance-editor mt-3">
                <div className="appearance-editor-heading">
                  <div>
                    <p className="text-xs font-bold text-[var(--color-text)]">背景与毛玻璃</p>
                    <p className="text-[11px] text-[var(--color-text-muted)] mt-1">
                      支持 HTTPS 图片地址或站内路径，留空恢复默认渐变背景。
                    </p>
                  </div>
                  <span className="appearance-preview-dot" aria-hidden="true" />
                </div>

                <input
                  value={backgroundImage}
                  onChange={(event) => setBackgroundImage(event.target.value)}
                  placeholder="背景图片 URL（https://... 或 /background.jpg）"
                  maxLength={2048}
                  className="panel-chip w-full text-xs px-3 py-2"
                  aria-label="自定义背景图片地址"
                />

                <div className="appearance-live-preview" aria-label="背景效果预览">
                  <div
                    className="appearance-live-preview-bg"
                    style={{
                      backgroundImage: backgroundImage.trim()
                        ? `url(${JSON.stringify(backgroundImage.trim())})`
                        : "linear-gradient(135deg, #f5afc8, #a6ddda 58%, #f3cf82)",
                      filter: `blur(${Math.min(backgroundBlur, 12)}px)`,
                      opacity: backgroundOpacity / 100,
                    }}
                  />
                  <div
                    className="appearance-live-preview-glass"
                    style={{ backgroundColor: `rgba(255, 253, 247, ${glassOpacity / 100})` }}
                  >
                    <span />
                    <b>Glass preview</b>
                    <small>{backgroundBlur}px · {backgroundOpacity}% · {glassOpacity}%</small>
                  </div>
                </div>

                <div className="appearance-sliders">
                  <AppearanceSlider
                    label="背景模糊"
                    value={backgroundBlur}
                    min={0}
                    max={30}
                    unit="px"
                    onChange={setBackgroundBlur}
                  />
                  <AppearanceSlider
                    label="背景不透明度"
                    value={backgroundOpacity}
                    min={0}
                    max={100}
                    unit="%"
                    onChange={setBackgroundOpacity}
                  />
                  <AppearanceSlider
                    label="玻璃不透明度"
                    value={glassOpacity}
                    min={20}
                    max={100}
                    unit="%"
                    onChange={setGlassOpacity}
                  />
                </div>
              </div>

              <div className="mt-2">
                <button
                  type="button"
                  onClick={() => {
                    onSaveSite({
                      displayName: siteDisplayName,
                      siteTitle,
                      siteDescription,
                      shieldEnabled,
                      shieldStatusText,
                      backgroundImage: backgroundImage.trim(),
                      backgroundBlur,
                      backgroundOpacity,
                      glassOpacity,
                    });
                  }}
                  className="pill-btn text-xs px-3 py-1"
                >
                  保存网页外观
                </button>
              </div>

              <div className="separator-dashed my-3" />
              <p className="text-xs font-bold uppercase tracking-[0.2em] text-[var(--color-text-muted)] mb-2">
                主面板设备（Token 与名称）
              </p>
              <p className="text-xs text-[var(--color-text-muted)] mb-2">
                新增后会立刻出现在主面板。设备尚未上报时，会先显示为离线。
              </p>

              <div className="grid gap-2 md:grid-cols-2">
                <input
                  value={deviceId}
                  onChange={(event) => setDeviceId(event.target.value)}
                  placeholder="设备 ID（如: phone-1）"
                  className="panel-chip w-full text-xs px-3 py-2"
                />
                <input
                  value={deviceName}
                  onChange={(event) => setDeviceName(event.target.value)}
                  placeholder="设备名称（如: Vivo X200）"
                  className="panel-chip w-full text-xs px-3 py-2"
                />
                <input
                  value={deviceToken}
                  onChange={(event) => setDeviceToken(event.target.value)}
                  placeholder="设备 Token"
                  className="panel-chip w-full text-xs px-3 py-2"
                />
                <select
                  value={devicePlatform}
                  onChange={(event) => setDevicePlatform(event.target.value as "windows" | "android" | "macos")}
                  className="panel-chip w-full text-xs px-3 py-2"
                >
                  <option value="windows">windows</option>
                  <option value="android">android</option>
                  <option value="macos">macos</option>
                </select>
              </div>

              <div className="mt-2 flex flex-wrap gap-2">
                <button
                  type="button"
                  onClick={() => {
                    onSaveDevice({
                      token: deviceToken.trim(),
                      device_id: deviceId.trim(),
                      device_name: deviceName.trim(),
                      platform: devicePlatform,
                    });
                  }}
                  className="pill-btn text-xs px-3 py-1"
                >
                  添加 / 更新主面板设备
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setDeviceToken("");
                    setDeviceId("");
                    setDeviceName("");
                    setDevicePlatform("windows");
                  }}
                  className="pill-btn text-xs px-3 py-1"
                >
                  清空输入
                </button>
              </div>

              {devices.length > 0 && (
                <div className="mt-3 space-y-2">
                  {devices.map((device) => (
                    <div
                      key={`${device.source}-${device.device_id}`}
                      className="panel-chip flex flex-wrap items-center justify-between gap-2 text-xs px-3 py-2"
                    >
                      <span>
                        {device.device_name} ({device.device_id}) · {device.platform} · {device.source}
                      </span>
                      <div className="flex gap-2">
                        <button
                          type="button"
                          onClick={() => applyDeviceToForm(device)}
                          className="pill-btn text-xs px-2 py-1"
                        >
                          编辑
                        </button>
                        {device.source === "runtime" && (
                          <button
                            type="button"
                            onClick={() => onDeleteDevice(device.device_id)}
                            className="pill-btn text-xs px-2 py-1"
                          >
                            删除主面板设备
                          </button>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}

          {adminStatus && (
            <p className="text-xs text-[var(--color-text-muted)] mt-2">{adminStatus}</p>
          )}
        </>
      )}
    </section>
  );
}

function AppearanceSlider({
  label,
  value,
  min,
  max,
  unit,
  onChange,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  unit: string;
  onChange: (value: number) => void;
}) {
  return (
    <label className="appearance-slider">
      <span>
        {label}
        <b>{value}{unit}</b>
      </span>
      <input
        type="range"
        min={min}
        max={max}
        value={value}
        onChange={(event) => onChange(Number(event.target.value))}
      />
    </label>
  );
}

function DashboardOverviewCard({
  dashboard,
  selected,
  onSelect,
}: {
  dashboard: DashboardSnapshot;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`dashboard-overview-card text-left ${selected ? "dashboard-overview-card-active" : ""}`}
    >
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-sm font-semibold text-[var(--color-text)]">{dashboard.name}</p>
          <p className="text-[11px] text-[var(--color-text-muted)] mt-1 line-clamp-2">
            {dashboard.description ?? "Live Dashboard 聚合面板"}
          </p>
        </div>
        <span className={`status-pill ${dashboard.onlineDevices > 0 ? "status-pill-online" : "status-pill-offline"}`}>
          {dashboard.statusText}
        </span>
      </div>

      <div className="mt-4 grid grid-cols-3 gap-2 text-center">
        <div>
          <p className="text-[10px] uppercase tracking-wide text-[var(--color-text-muted)]">Devices</p>
          <p className="text-lg font-semibold text-[var(--color-text)]">{dashboard.onlineDevices}/{dashboard.totalDevices}</p>
        </div>
        <div>
          <p className="text-[10px] uppercase tracking-wide text-[var(--color-text-muted)]">Viewers</p>
          <p className="text-lg font-semibold text-[var(--color-text)]">{dashboard.viewerCount}</p>
        </div>
        <div>
          <p className="text-[10px] uppercase tracking-wide text-[var(--color-text-muted)]">Status</p>
          <p className="text-sm font-semibold text-[var(--color-text)] truncate">{dashboard.activeLabel}</p>
        </div>
      </div>
    </button>
  );
}

const platformIcons: Record<string, string> = {
  windows: "\u{1F5A5}",
  android: "\u{1F4F1}",
};

function DeviceOverview({ devices }: { devices: DeviceState[] }) {
  return (
    <div className="mb-3 flex flex-wrap items-center gap-x-4 gap-y-0.5 text-[11px] text-[var(--color-text-muted)]">
      {devices.map((device) => {
        const isOnline = device.is_online === 1;
        const icon = platformIcons[device.platform] || "\u{1F4BB}";
        return (
          <span key={device.device_id} className={isOnline ? "" : "opacity-40"}>
            {icon} {device.device_name} · {isOnline ? (device.app_name === "idle" ? "暂时离开" : device.app_name || "idle") : "offline"}
          </span>
        );
      })}
    </div>
  );
}
