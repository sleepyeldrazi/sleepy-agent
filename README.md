---
type: project
machine: MacBook Pro
status: active
git:
  type: personal
  private: true
---

# Sleepy Agent

Privacy-first Android AI assistant using Google Gemma 4 (E2B/E4B) on-device inference via MediaPipe. Features voice input with Silero VAD, camera for visual queries, native TTS output, and optional delegation to a local big model via Tailscale.

## Features

- **Voice input**: Push-to-talk with silence detection (VAD)
- **Camera**: Take photos for visual analysis
- **TTS**: Native Android TextToSpeech for responses
- **Local inference**: E2B/E4B models via MediaPipe LLM Inference
- **Web search**: Built-in via SearXNG (`sleepy-think`)
- **Remote delegation**: Route complex tasks to big local model via Tailscale
- **Long-term memory**: RAG pipeline over conversation history (future)

## Architecture

```
┌──────────────────────────────────────┐
│  Phone (E2B/E4B - on-device)         │
│  - Voice/Camera → Local inference    │
│  - Web search (SearXNG)              │
│  - Delegate heavy tasks to server    │
│  - TTS output                        │
└──────────────────────────────────────┘
         │ Tailscale
         ▼
┌──────────────────────────────────────┐
│  Home server                         │
│  - Ollama/vLLM (big model)           │
│  - OpenAI-compatible API             │
└──────────────────────────────────────┘
```

## Tech Stack

- Kotlin + Jetpack Compose (Material 3)
- MediaPipe LLM Inference (Gemma 4 E2B/E4B)
- Silero VAD (voice activity detection)
- CameraX
- Native Android TextToSpeech
- Ktor Client (server API)
- Hilt (dependency injection)
- DataStore (preferences)

## Location

```
~/Documents/personal/sleepy_agent
```

## Related

- [[Local-Swarm]] - Multi-LLM consensus
- [[Light-Chat]] - Android LLM chat app

## Git

- No remote configured
- Type: Personal, Private

---

*Last Update: 2026-04-05*
