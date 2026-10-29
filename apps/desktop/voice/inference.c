#include "c-api.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifndef _WIN32
#include <pthread.h>
#include <unistd.h>
#include <time.h>
static pid_t owner;
static void *watch_owner(void *unused) {
  (void)unused;
  struct timespec pause = {0, 250000000};
  for (int tick = 0; tick < 480; ++tick) {
    nanosleep(&pause, NULL);
    if (getppid() != owner) _exit(6);
  }
  _exit(7);
}
#endif

// Each invocation owns one inference. Killing it cancels work and releases model memory.
static char *path(const char *root, const char *name) {
  size_t n = strlen(root) + strlen(name) + 2;
  char *p = malloc(n);
  if (!p) exit(2);
  snprintf(p, n, "%s/%s", root, name);
  return p;
}
static void le32(uint32_t x) { for (int i = 0; i < 4; ++i) putchar((x >> (i * 8)) & 255); }
static void le16(uint16_t x) { putchar(x & 255); putchar(x >> 8); }
int main(int argc, char **argv) {
  if (argc < 4) return 2;
#ifndef _WIN32
  owner = getppid();
  if (owner == 1) return 6;
  pthread_t watchdog;
  if (pthread_create(&watchdog, NULL, watch_owner, NULL)) return 2;
  pthread_detach(watchdog);
#endif
  const char *op = argv[1], *root = argv[2], *language = argv[3];
  if (!strcmp(op, "transcribe")) {
    if (argc != 5) return 2;
    char name[128];
    SherpaOnnxOfflineRecognizerConfig c = {0};
    c.feat_config.sample_rate = 16000;
    c.feat_config.feature_dim = 80;
    snprintf(name, sizeof(name), "%s-encoder.int8.onnx", argv[4]);
    c.model_config.whisper.encoder = path(root, name);
    snprintf(name, sizeof(name), "%s-decoder.int8.onnx", argv[4]);
    c.model_config.whisper.decoder = path(root, name);
    snprintf(name, sizeof(name), "%s-tokens.txt", argv[4]);
    c.model_config.tokens = path(root, name);
    c.model_config.whisper.language = language;
    c.model_config.whisper.task = "transcribe";
    c.model_config.num_threads = 2;
    c.model_config.provider = "cpu";
    c.decoding_method = "greedy_search";
    const SherpaOnnxOfflineRecognizer *r = SherpaOnnxCreateOfflineRecognizer(&c);
    if (!r) return 3;
    float *samples = calloc(16000 * 30, sizeof(float));
    if (!samples) return 2;
    size_t n = fread(samples, sizeof(float), 16000 * 30, stdin);
    if (!n || fgetc(stdin) != EOF) return 4;
    const SherpaOnnxOfflineStream *stream = SherpaOnnxCreateOfflineStream(r);
    if (!stream) return 3;
    SherpaOnnxAcceptWaveformOffline(stream, 16000, samples, (int32_t)n);
    SherpaOnnxDecodeOfflineStream(r, stream);
    const SherpaOnnxOfflineRecognizerResult *result = SherpaOnnxGetOfflineStreamResult(stream);
    if (!result) return 3;
    fputs(result->text, stdout);
    SherpaOnnxDestroyOfflineRecognizerResult(result);
    SherpaOnnxDestroyOfflineStream(stream);
    SherpaOnnxDestroyOfflineRecognizer(r);
    free(samples);
  } else if (!strcmp(op, "synthesize")) {
    SherpaOnnxOfflineTtsConfig c = {0};
    c.model.num_threads = 2;
    c.model.provider = "cpu";
    c.model.supertonic.duration_predictor = path(root, "duration_predictor.int8.onnx");
    c.model.supertonic.text_encoder = path(root, "text_encoder.int8.onnx");
    c.model.supertonic.vector_estimator = path(root, "vector_estimator.int8.onnx");
    c.model.supertonic.vocoder = path(root, "vocoder.int8.onnx");
    c.model.supertonic.tts_json = path(root, "tts.json");
    c.model.supertonic.unicode_indexer = path(root, "unicode_indexer.bin");
    c.model.supertonic.voice_style = path(root, "voice.bin");
    c.max_num_sentences = 1;
    const SherpaOnnxOfflineTts *tts = SherpaOnnxCreateOfflineTts(&c);
    if (!tts) return 3;
    char text[8193] = {0};
    size_t n = fread(text, 1, sizeof(text)-1, stdin);
    if (!n || fgetc(stdin) != EOF) return 4;
    SherpaOnnxGenerationConfig g = {0};
    g.speed = 1.0f; g.sid = 0; g.num_steps = 5; g.silence_scale = 0.2f;
    char extra[64];
    if (strlen(language) > 3) return 2;
    snprintf(extra, sizeof(extra), "{\"lang\":\"%s\"}", language);
    g.extra = extra;
    const SherpaOnnxGeneratedAudio *a = SherpaOnnxOfflineTtsGenerateWithConfig(tts, text, &g, NULL, NULL);
    if (!a || a->n <= 0 || a->n > a->sample_rate * 120) return 3;
    fwrite("RIFF", 1, 4, stdout); le32(36 + a->n * 2); fwrite("WAVEfmt ", 1, 8, stdout);
    le32(16); le16(1); le16(1); le32(a->sample_rate); le32(a->sample_rate * 2); le16(2); le16(16);
    fwrite("data", 1, 4, stdout); le32(a->n * 2);
    for (int i = 0; i < a->n; ++i) {
      float f = a->samples[i]; if (f > 1) f = 1; if (f < -1) f = -1;
      le16((uint16_t)(int16_t)(f * 32767));
    }
    SherpaOnnxDestroyOfflineTtsGeneratedAudio(a);
    SherpaOnnxDestroyOfflineTts(tts);
  } else return 2;
  return ferror(stdout) ? 5 : 0;
}
