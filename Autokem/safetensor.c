#include "safetensor.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

/* Tensor registry entry */
typedef struct {
    const char *name;
    float *data;
    int size;
    int ndim;
    int shape[4];
} TensorEntry;

static void collect_tensors(Network *net, TensorEntry *entries, int *count) {
    int n = 0;
#define ADD(nm, layer, field) do { \
    entries[n].name = nm; \
    entries[n].data = net->layer.field->data; \
    entries[n].size = net->layer.field->size; \
    entries[n].ndim = net->layer.field->ndim; \
    for (int i = 0; i < net->layer.field->ndim; i++) \
        entries[n].shape[i] = net->layer.field->shape[i]; \
    n++; \
} while(0)

    ADD("conv1.weight", conv1, weight);
    ADD("conv1.bias", conv1, bias);
    ADD("conv2.weight", conv2, weight);
    ADD("conv2.bias", conv2, bias);
    ADD("fc1.weight", fc1, weight);
    ADD("fc1.bias", fc1, bias);
    ADD("output.weight", output, weight);
    ADD("output.bias", output, bias);
#undef ADD
    *count = n;
}

int safetensor_save(const char *path, Network *net, int total_samples, int epochs, float val_loss) {
    TensorEntry entries[8];
    int count;
    collect_tensors(net, entries, &count);

    /* Build JSON header */
    char header[8192];
    int pos = 0;
    pos += snprintf(header + pos, sizeof(header) - (size_t)pos, "{");

    /* metadata */
    pos += snprintf(header + pos, sizeof(header) - (size_t)pos,
        "\"__metadata__\":{\"samples\":\"%d\",\"epochs\":\"%d\",\"val_loss\":\"%.6f\"},",
        total_samples, epochs, (double)val_loss);

    /* tensor entries */
    size_t data_offset = 0;
    for (int i = 0; i < count; i++) {
        size_t byte_size = (size_t)entries[i].size * sizeof(float);

        pos += snprintf(header + pos, sizeof(header) - (size_t)pos,
            "\"%s\":{\"dtype\":\"F32\",\"shape\":[", entries[i].name);

        for (int d = 0; d < entries[i].ndim; d++) {
            if (d > 0) pos += snprintf(header + pos, sizeof(header) - (size_t)pos, ",");
            pos += snprintf(header + pos, sizeof(header) - (size_t)pos, "%d", entries[i].shape[d]);
        }

        pos += snprintf(header + pos, sizeof(header) - (size_t)pos,
            "],\"data_offsets\":[%zu,%zu]}", data_offset, data_offset + byte_size);

        if (i < count - 1)
            pos += snprintf(header + pos, sizeof(header) - (size_t)pos, ",");

        data_offset += byte_size;
    }

    pos += snprintf(header + pos, sizeof(header) - (size_t)pos, "}");

    /* Pad header to 8-byte alignment */
    size_t header_len = (size_t)pos;
    size_t padded = (header_len + 7) & ~(size_t)7;
    while (header_len < padded) {
        header[header_len++] = ' ';
    }

    FILE *f = fopen(path, "wb");
    if (!f) {
        fprintf(stderr, "Error: cannot open %s for writing\n", path);
        return -1;
    }

    /* 8-byte LE header length */
    uint64_t hlen = (uint64_t)header_len;
    fwrite(&hlen, 8, 1, f);

    /* JSON header */
    fwrite(header, 1, header_len, f);

    /* Raw tensor data */
    for (int i = 0; i < count; i++) {
        fwrite(entries[i].data, sizeof(float), (size_t)entries[i].size, f);
    }

    fclose(f);
    printf("Saved model to %s (%zu bytes)\n", path, 8 + header_len + data_offset);
    return 0;
}

/* Minimal JSON parser: find tensor by name, extract data_offsets */
static int find_tensor_offsets(const char *json, size_t json_len, const char *name,
                               size_t *off_start, size_t *off_end) {
    /* Search for "name": */
    size_t nlen = strlen(name);
    for (size_t i = 0; i + nlen + 3 < json_len; i++) {
        if (json[i] == '"' && strncmp(json + i + 1, name, nlen) == 0 && json[i + 1 + nlen] == '"') {
            /* Found the key, now find data_offsets */
            const char *doff = strstr(json + i, "\"data_offsets\"");
            if (!doff || (size_t)(doff - json) > json_len) return -1;
            const char *bracket = strchr(doff, '[');
            if (!bracket) return -1;
            if (sscanf(bracket, "[%zu,%zu]", off_start, off_end) != 2) return -1;
            return 0;
        }
    }
    return -1;
}

int safetensor_load(const char *path, Network *net) {
    FILE *f = fopen(path, "rb");
    if (!f) {
        fprintf(stderr, "Error: cannot open %s\n", path);
        return -1;
    }

    uint64_t header_len;
    if (fread(&header_len, 8, 1, f) != 1) { fclose(f); return -1; }

    char *json = malloc((size_t)header_len + 1);
    if (fread(json, 1, (size_t)header_len, f) != (size_t)header_len) {
        free(json);
        fclose(f);
        return -1;
    }
    json[header_len] = '\0';

    long data_start = 8 + (long)header_len;

    TensorEntry entries[8];
    int count;
    collect_tensors(net, entries, &count);

    for (int i = 0; i < count; i++) {
        size_t off_start, off_end;
        if (find_tensor_offsets(json, (size_t)header_len, entries[i].name, &off_start, &off_end) != 0) {
            fprintf(stderr, "Error: tensor '%s' not found in %s\n", entries[i].name, path);
            free(json);
            fclose(f);
            return -1;
        }

        size_t byte_size = off_end - off_start;
        if (byte_size != (size_t)entries[i].size * sizeof(float)) {
            fprintf(stderr, "Error: size mismatch for '%s': expected %zu, got %zu\n",
                    entries[i].name, (size_t)entries[i].size * sizeof(float), byte_size);
            free(json);
            fclose(f);
            return -1;
        }

        fseek(f, data_start + (long)off_start, SEEK_SET);
        if (fread(entries[i].data, 1, byte_size, f) != byte_size) {
            fprintf(stderr, "Error: failed to read tensor '%s'\n", entries[i].name);
            free(json);
            fclose(f);
            return -1;
        }
    }

    free(json);
    fclose(f);
    return 0;
}

int safetensor_stats(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) {
        fprintf(stderr, "Error: cannot open %s\n", path);
        return -1;
    }

    uint64_t header_len;
    if (fread(&header_len, 8, 1, f) != 1) { fclose(f); return -1; }

    char *json = malloc((size_t)header_len + 1);
    if (fread(json, 1, (size_t)header_len, f) != (size_t)header_len) {
        free(json);
        fclose(f);
        return -1;
    }
    json[header_len] = '\0';
    fclose(f);

    printf("Model: %s\n", path);
    printf("Header length: %lu bytes\n", (unsigned long)header_len);

    /* Extract a JSON string value: find "key":"value" and return value */
    /* Helper: find value for key within metadata block */
    const char *meta = strstr(json, "\"__metadata__\"");
    if (meta) {
        const char *keys[] = {"samples", "epochs", "val_loss"};
        const char *labels[] = {"Training samples", "Epochs", "Validation loss"};
        for (int k = 0; k < 3; k++) {
            char search[64];
            snprintf(search, sizeof(search), "\"%s\"", keys[k]);
            const char *found = strstr(meta, search);
            if (!found) continue;
            /* skip past key and colon to opening quote of value */
            const char *colon = strchr(found + strlen(search), ':');
            if (!colon) continue;
            const char *vstart = strchr(colon, '"');
            if (!vstart) continue;
            vstart++;
            const char *vend = strchr(vstart, '"');
            if (!vend) continue;
            printf("%s: %.*s\n", labels[k], (int)(vend - vstart), vstart);
        }
    }

    /* List tensors */
    const char *tensor_names[] = {
        "conv1.weight", "conv1.bias", "conv2.weight", "conv2.bias",
        "fc1.weight", "fc1.bias",
        "output.weight", "output.bias"
    };

    int total_params = 0;
    printf("\nTensors:\n");
    for (int i = 0; i < 8; i++) {
        size_t off_start, off_end;
        if (find_tensor_offsets(json, (size_t)header_len, tensor_names[i], &off_start, &off_end) == 0) {
            int params = (int)(off_end - off_start) / 4;
            total_params += params;

            /* Extract shape */
            const char *key = strstr(json, tensor_names[i]);
            if (key) {
                const char *shp = strstr(key, "\"shape\"");
                if (shp) {
                    const char *br = strchr(shp, '[');
                    const char *bre = strchr(shp, ']');
                    if (br && bre) {
                        printf("  %-28s shape=[%.*s]  params=%d\n",
                               tensor_names[i], (int)(bre - br - 1), br + 1, params);
                    }
                }
            }
        }
    }
    printf("\nTotal parameters: %d (%.1f KB as float32)\n", total_params, (float)total_params * 4.0f / 1024.0f);

    free(json);
    return 0;
}
