"use client";

import { useState, useEffect, useCallback, useMemo } from "react";
import {
  fetchCurrent,
  fetchTimeline,
  type DashboardRequestOptions,
  type CurrentResponse,
  type TimelineResponse,
} from "@/lib/api";

const CURRENT_POLL_INTERVAL = 10 * 1000;
const TIMELINE_POLL_INTERVAL = 30 * 1000;

function todayStr(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

export function useDashboard(dashboardId?: string, adminToken?: string) {
  const [current, setCurrent] = useState<CurrentResponse | null>(null);
  const [timeline, setTimeline] = useState<TimelineResponse | null>(null);
  const [selectedDate, setSelectedDate] = useState("");
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [timelineLoading, setTimelineLoading] = useState(true);
  const [timelineRefreshing, setTimelineRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [viewerCount, setViewerCount] = useState(0);
  const requestOptions = useMemo<DashboardRequestOptions | undefined>(() => {
    if (!dashboardId && !adminToken) return undefined;
    return { dashboardId, adminToken };
  }, [adminToken, dashboardId]);

  useEffect(() => {
    if (!selectedDate) setSelectedDate(todayStr());
  }, [selectedDate]);

  useEffect(() => {
    const controller = new AbortController();
    let requestId = 0;

    const doFetchCurrent = async (initial = false) => {
      const thisRequest = ++requestId;
      const isActive = () => !controller.signal.aborted && thisRequest === requestId;
      if (!initial) setRefreshing(true);

      try {
        const cur = await fetchCurrent(controller.signal, requestOptions);
        if (isActive()) {
          setCurrent(cur);
          setViewerCount(cur.viewer_count ?? 0);
          setError(null);
          setLoading(false);
        }
      } catch (e) {
        if (isActive()) {
          setError(e instanceof Error ? e.message : "Failed to fetch data");
          setLoading(false);
        }
      } finally {
        if (isActive()) setRefreshing(false);
      }
    };

    setCurrent(null);
    setTimeline(null);
    setViewerCount(0);
    setLoading(true);
    setRefreshing(false);
    void doFetchCurrent(true);
    const pollId = setInterval(doFetchCurrent, CURRENT_POLL_INTERVAL);

    return () => {
      controller.abort();
      clearInterval(pollId);
    };
  }, [requestOptions]);

  useEffect(() => {
    if (!selectedDate) return;

    const controller = new AbortController();
    let requestId = 0;

    const doFetchTimeline = async (initial = false) => {
      const thisRequest = ++requestId;
      if (initial) {
        setTimelineLoading(true);
      } else {
        setTimelineRefreshing(true);
      }
      try {
        const tl = await fetchTimeline(selectedDate, controller.signal, requestOptions);
        if (!controller.signal.aborted && thisRequest === requestId) {
          setTimeline(tl);
        }
      } catch {
        // Keep stale timeline data if timeline refresh fails.
      } finally {
        if (!controller.signal.aborted && thisRequest === requestId) {
          setTimelineLoading(false);
          setTimelineRefreshing(false);
        }
      }
    };

    setTimeline(null);
    setTimelineRefreshing(false);
    void doFetchTimeline(true);
    const pollId = setInterval(doFetchTimeline, TIMELINE_POLL_INTERVAL);

    return () => {
      controller.abort();
      clearInterval(pollId);
    };
  }, [requestOptions, selectedDate]);

  const changeDate = useCallback((date: string) => {
    setSelectedDate(date);
  }, []);

  return {
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
  };
}
