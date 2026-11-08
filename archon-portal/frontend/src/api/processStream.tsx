import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { UNAUTHORIZED_EVENT } from "./http";
import type { ProcessSnapshot } from "./types";

/**
 * - `connecting`: waiting for the first event
 * - `live`: receiving changes as they happen
 * - `offline`: the portal is up, but no primary registry is answering
 * - `reconnecting`: the stream to the portal broke; the browser is retrying
 */
export type StreamStatus = "connecting" | "live" | "offline" | "reconnecting";

export interface ProcessStreamState {
  /** The newest process list, kept while offline so pages can show the last known state. */
  snapshot: ProcessSnapshot | null;
  status: StreamStatus;
  error: string | null;
}

export const ProcessStreamContext = createContext<ProcessStreamState | null>(null);

type SourceFactory = (url: string) => EventSource;

const openEventSource: SourceFactory = (url) => new EventSource(url);

interface ProviderProps {
  children: ReactNode;
  url?: string;
  /** Replaced in tests. */
  createSource?: SourceFactory;
}

/**
 * Keeps one stream of process lists open for the whole app. The portal pushes a new list whenever anything
 * changes, and EventSource reconnects by itself, possibly to another portal replica.
 */
export function ProcessStreamProvider({ children, url = "/api/events", createSource = openEventSource }: ProviderProps) {
  const [state, setState] = useState<ProcessStreamState>({ snapshot: null, status: "connecting", error: null });

  useEffect(() => {
    const source = createSource(url);
    const onSnapshot = (event: MessageEvent<string>) =>
      setState({ snapshot: JSON.parse(event.data) as ProcessSnapshot, status: "live", error: null });
    const onOffline = (event: MessageEvent<string>) => {
      const { error } = JSON.parse(event.data) as { error?: string };
      setState((previous) => ({ ...previous, status: "offline", error: error ?? "No registry is answering" }));
    };
    const onError = () =>
      setState((previous) => ({ ...previous, status: "reconnecting", error: "Lost the connection to the portal" }));

    // The portal ends the stream when the session does: signed out, expired, or the user was disabled
    const onSignedOut = () => {
      source.close();
      window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
    };

    source.addEventListener("snapshot", onSnapshot as EventListener);
    source.addEventListener("offline", onOffline as EventListener);
    source.addEventListener("signedout", onSignedOut);
    source.addEventListener("error", onError);
    return () => source.close();
  }, [url, createSource]);

  return <ProcessStreamContext.Provider value={state}>{children}</ProcessStreamContext.Provider>;
}

export function useProcessStream(): ProcessStreamState {
  const state = useContext(ProcessStreamContext);
  if (!state) {
    throw new Error("useProcessStream must be used inside ProcessStreamProvider");
  }
  return state;
}
