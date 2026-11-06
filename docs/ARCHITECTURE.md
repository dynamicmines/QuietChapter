# Architecture

## Playback ownership

`PlaybackService` extends Media3 `MediaLibraryService`. It owns the single ExoPlayer instance, foreground media notification, media session, catalog and bookmark persistence. The activity connects through a Media Browser; it never creates a second player.

The service exposes a compact audiobook hierarchy:

```text
QuietChapter library
└── A story that waits
    ├── Chapter 1 · The library
    └── Chapter 2 · The road
```

## Safety-critical precedence

The deterministic playback coordinator is the final authority:

1. Explicit user Pause wins.
2. Android audio-focus loss, calls, noisy output and source loss prevent automatic playback.
3. Untrusted, stale, duplicated or contradictory road context is rejected.
4. Only a fresh, admitted context may request a policy action.
5. A policy action can pause; it cannot bypass a higher-priority block to play.

## Narrative Recovery

The bundled demo contains a cached timeline with annotation provenance and reliability. The controller may schedule a short soft pause only when a trusted upcoming interruption is available. Immediate platform interruptions always pause immediately. On recovery, the controller chooses an eligible previously heard boundary only when it is valid for the current chapter and revision; otherwise it resumes exactly or uses the conservative fallback.

The timeline is not a claim of general language understanding. Acoustic and sentence annotations have distinct provenance, and failed/missing analysis is a safe fallback condition.

## Road-context boundary

The production app contains a `PleosNavigationSource` boundary but no enabled native route-to-playback connection. Its Road Context demonstration uses an explicitly labelled simulation. Separate private experiments observed genuine location callbacks, but did not establish authoritative correspondence with the route selected in native Pleos Navigation. This repository does not substitute an unrelated route as if it were native navigation.
