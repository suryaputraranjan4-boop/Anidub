# Anydub Self-Hosted GPU Inference Worker

Companion FastAPI server implementing the remote PyTorch/CUDA inference endpoints used by the Android app:

- `GET /v1/health` — Reports CUDA/device telemetry and per-model `downloaded`, `loaded`, and `ready` status.
- `POST /v1/separate` — 2-stem vocal/background separation (`adefossez/HTDemucs`).
- `POST /v1/transcribe` — Japanese ASR + word-level timestamps (`openai/whisper-large-v3-turbo`).
- `POST /v1/diarize` — Neural speaker diarization (`pyannote/speaker-diarization-3.1`).
- `POST /v1/synthesize` — Hindi voice synthesis (`SPRINGLab/F5-Hindi-24KHz`).

## Running Locally or on a GPU Server

```bash
pip install -r worker/requirements.txt
export ANYDUB_MODELS_DIR=/path/to/downloaded_models
export ANYDUB_AUTH_TOKEN=your-secret-token
export HF_TOKEN=hf_your_readonly_token   # Required only for gated pyannote 3.1
uvicorn worker.server:app --host 0.0.0.0 --port 8080
```

Enter the server's HTTPS URL (or LAN HTTP URL with **Allow HTTP on Private LAN** enabled) and Bearer token in **Studio Settings** inside the Android app.
