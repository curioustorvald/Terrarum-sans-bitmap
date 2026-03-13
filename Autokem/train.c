#include "train.h"
#include "tga.h"
#include "nn.h"
#include "safetensor.h"
#include "unicode_lm.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include <dirent.h>

/* ---- Data sample ---- */

typedef struct {
    float input[300];   /* 15x20 binary */
    float shape[10];    /* A,B,C,D,E,F,G,H,J,K */
    float ytype;
    float lowheight;
} Sample;

/* ---- Bit extraction from kerning mask ---- */
/* kerningMask = pixel >> 8 & 0xFFFFFF
 * Layout: Red=Y0000000, Green=JK000000, Blue=ABCDEFGH
 * After >> 8: bits 23-16 = Red[7:0], bits 15-8 = Green[7:0], bits 7-0 = Blue[7:0]
 * Y = bit 23 (already extracted separately as isKernYtype)
 * J = bit 15, K = bit 14
 * A = bit 7, B = bit 6, ..., H = bit 0
 */
static void extract_shape_bits(int kerning_mask, float *shape) {
    shape[0] = (float)((kerning_mask >> 7) & 1); /* A */
    shape[1] = (float)((kerning_mask >> 6) & 1); /* B */
    shape[2] = (float)((kerning_mask >> 5) & 1); /* C */
    shape[3] = (float)((kerning_mask >> 4) & 1); /* D */
    shape[4] = (float)((kerning_mask >> 3) & 1); /* E */
    shape[5] = (float)((kerning_mask >> 2) & 1); /* F */
    shape[6] = (float)((kerning_mask >> 1) & 1); /* G */
    shape[7] = (float)((kerning_mask >> 0) & 1); /* H */
    shape[8] = (float)((kerning_mask >> 15) & 1); /* J */
    shape[9] = (float)((kerning_mask >> 14) & 1); /* K */
}

/* ---- Collect samples from one TGA ---- */

static int collect_from_sheet(const char *path, int is_xyswap, int start_code,
                              Sample *samples, int max_samples) {
    TgaImage *img = tga_read(path);
    if (!img) {
        fprintf(stderr, "Warning: cannot read %s\n", path);
        return 0;
    }

    int cell_w = 16, cell_h = 20;
    int cols = img->width / cell_w;
    int rows = img->height / cell_h;
    int total_cells = cols * rows;
    int count = 0;

    for (int index = 0; index < total_cells && count < max_samples; index++) {
        int cell_x, cell_y;
        if (is_xyswap) {
            cell_x = (index / cols) * cell_w;
            cell_y = (index % cols) * cell_h;
        } else {
            cell_x = (index % cols) * cell_w;
            cell_y = (index / cols) * cell_h;
        }

        int tag_x = cell_x + (cell_w - 1); /* rightmost column */
        int tag_y = cell_y;

        /* Read width (5-bit binary from Y+0..Y+4) */
        int width = 0;
        for (int y = 0; y < 5; y++) {
            if (tga_get_pixel(img, tag_x, tag_y + y) & 0xFF)
                width |= (1 << y);
        }
        if (width == 0) continue;

        /* Skip modifier letters (superscripts/subscripts) */
        if (start_code >= 0 && is_modifier_letter(start_code + index))
            continue;

        /* Read kerning data pixel at Y+6 */
        uint32_t kern_pixel = tagify(tga_get_pixel(img, tag_x, tag_y + 6));
        if ((kern_pixel & 0xFF) == 0) continue; /* no kern data */

        /* Extract labels */
        int is_kern_ytype = (kern_pixel & 0x80000000u) != 0;
        int kerning_mask = (int)((kern_pixel >> 8) & 0xFFFFFF);

        int is_low_height = (tga_get_pixel(img, tag_x, tag_y + 5) & 0xFF) != 0;

        Sample *s = &samples[count];
        extract_shape_bits(kerning_mask, s->shape);
        s->ytype = (float)is_kern_ytype;
        s->lowheight = (float)is_low_height;

        /* Extract 15x20 binary input from the glyph area */
        for (int gy = 0; gy < 20; gy++) {
            for (int gx = 0; gx < 15; gx++) {
                uint32_t p = tga_get_pixel(img, cell_x + gx, cell_y + gy);
                s->input[gy * 15 + gx] = ((p & 0x80) != 0) ? 1.0f : 0.0f;
            }
        }

        count++;
    }

    tga_free(img);
    return count;
}

/* ---- Fisher-Yates shuffle ---- */

static void shuffle_indices(int *arr, int n) {
    for (int i = n - 1; i > 0; i--) {
        int j = rand() % (i + 1);
        int tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
    }
}

/* ---- Copy network weights ---- */

static void copy_tensor_data(Tensor *dst, Tensor *src) {
    memcpy(dst->data, src->data, (size_t)src->size * sizeof(float));
}

static void save_weights(Network *net, Network *best) {
    copy_tensor_data(best->conv1.weight, net->conv1.weight);
    copy_tensor_data(best->conv1.bias, net->conv1.bias);
    copy_tensor_data(best->conv2.weight, net->conv2.weight);
    copy_tensor_data(best->conv2.bias, net->conv2.bias);
    copy_tensor_data(best->fc1.weight, net->fc1.weight);
    copy_tensor_data(best->fc1.bias, net->fc1.bias);
    copy_tensor_data(best->output.weight, net->output.weight);
    copy_tensor_data(best->output.bias, net->output.bias);
}

/* ---- Training ---- */

int train_model(void) {
    const char *assets_dir = "../src/assets";
    const int max_total = 16384;

    Sample *all_samples = calloc((size_t)max_total, sizeof(Sample));
    if (!all_samples) { fprintf(stderr, "Error: out of memory\n"); return 1; }

    int total = 0;

    /* Scan for *_variable.tga files */
    DIR *dir = opendir(assets_dir);
    if (!dir) {
        fprintf(stderr, "Error: cannot open %s\n", assets_dir);
        free(all_samples);
        return 1;
    }

    struct dirent *ent;
    int file_count = 0;
    while ((ent = readdir(dir)) != NULL) {
        const char *name = ent->d_name;
        size_t len = strlen(name);

        /* Must end with _variable.tga */
        if (len < 14) continue;
        if (strcmp(name + len - 13, "_variable.tga") != 0) continue;

        /* Skip extrawide */
        if (strstr(name, "extrawide") != NULL) continue;

        /* Check for xyswap */
        int is_xyswap = (strstr(name, "xyswap") != NULL);

        char fullpath[512];
        snprintf(fullpath, sizeof(fullpath), "%s/%s", assets_dir, name);

        int start_code = sheet_start_code(name);
        int got = collect_from_sheet(fullpath, is_xyswap, start_code,
                                     all_samples + total, max_total - total);
        if (got > 0) {
            printf("  %s: %d samples\n", name, got);
            total += got;
            file_count++;
        }
    }
    closedir(dir);

    printf("Collected %d samples from %d sheets\n", total, file_count);
    if (total < 10) {
        fprintf(stderr, "Error: too few samples to train\n");
        free(all_samples);
        return 1;
    }

    /* Print label distribution */
    {
        const char *bit_names[] = {"A","B","C","D","E","F","G","H","J","K","Ytype","LowH"};
        int counts[12] = {0};
        int nonzero_input = 0;
        for (int i = 0; i < total; i++) {
            for (int b = 0; b < 10; b++)
                counts[b] += (int)all_samples[i].shape[b];
            counts[10] += (int)all_samples[i].ytype;
            counts[11] += (int)all_samples[i].lowheight;
            for (int p = 0; p < 300; p++)
                if (all_samples[i].input[p] > 0.5f) { nonzero_input++; break; }
        }
        printf("Label distribution:\n  ");
        for (int b = 0; b < 12; b++)
            printf("%s:%d(%.0f%%) ", bit_names[b], counts[b], 100.0 * counts[b] / total);
        printf("\n  Non-empty inputs: %d/%d\n\n", nonzero_input, total);
    }

    /* Shuffle and split 80/20 */
    srand((unsigned)time(NULL));
    int *indices = malloc((size_t)total * sizeof(int));
    for (int i = 0; i < total; i++) indices[i] = i;
    shuffle_indices(indices, total);

    int n_train = (int)(total * 0.8);
    int n_val = total - n_train;
    printf("Train: %d, Validation: %d\n\n", n_train, n_val);

    /* Create network */
    Network *net = network_create();
    Network *best_net = network_create();

    int batch_size = 32;
    float lr = 0.001f, beta1 = 0.9f, beta2 = 0.999f, eps = 1e-8f;
    int max_epochs = 200;
    int patience = 10;
    float best_val_loss = 1e30f;
    int patience_counter = 0;
    int best_epoch = 0;
    int adam_t = 0;

    for (int epoch = 0; epoch < max_epochs; epoch++) {
        /* Shuffle training indices */
        shuffle_indices(indices, n_train);

        float train_loss = 0.0f;
        int n_batches = 0;

        /* Training loop */
        for (int start = 0; start < n_train; start += batch_size) {
            int bs = (start + batch_size <= n_train) ? batch_size : (n_train - start);

            /* Build batch tensors */
            int ishape[] = {bs, 1, 20, 15};
            Tensor *input = tensor_alloc(4, ishape);

            int tshape[] = {bs, 12};
            Tensor *target = tensor_alloc(2, tshape);

            for (int i = 0; i < bs; i++) {
                Sample *s = &all_samples[indices[start + i]];
                memcpy(input->data + i * 300, s->input, 300 * sizeof(float));
                memcpy(target->data + i * 12, s->shape, 10 * sizeof(float));
                target->data[i * 12 + 10] = s->ytype;
                target->data[i * 12 + 11] = s->lowheight;
            }

            /* Forward */
            network_zero_grad(net);
            network_forward(net, input, 1);

            /* Loss */
            float loss = network_bce_loss(net, target);
            train_loss += loss;
            n_batches++;

            /* Backward */
            network_backward(net, target);

            /* Adam step */
            adam_t++;
            network_adam_step(net, lr, beta1, beta2, eps, adam_t);

            tensor_free(input);
            tensor_free(target);
        }

        train_loss /= (float)n_batches;

        /* Validation */
        float val_loss = 0.0f;
        int val_batches = 0;
        for (int start = 0; start < n_val; start += batch_size) {
            int bs = (start + batch_size <= n_val) ? batch_size : (n_val - start);

            int ishape[] = {bs, 1, 20, 15};
            Tensor *input = tensor_alloc(4, ishape);

            int tshape[] = {bs, 12};
            Tensor *target = tensor_alloc(2, tshape);

            for (int i = 0; i < bs; i++) {
                Sample *s = &all_samples[indices[n_train + start + i]];
                memcpy(input->data + i * 300, s->input, 300 * sizeof(float));
                memcpy(target->data + i * 12, s->shape, 10 * sizeof(float));
                target->data[i * 12 + 10] = s->ytype;
                target->data[i * 12 + 11] = s->lowheight;
            }

            network_forward(net, input, 0);
            val_loss += network_bce_loss(net, target);
            val_batches++;

            tensor_free(input);
            tensor_free(target);
        }

        val_loss /= (float)val_batches;

        printf("Epoch %3d: train_loss=%.4f  val_loss=%.4f", epoch + 1, (double)train_loss, (double)val_loss);

        if (val_loss < best_val_loss) {
            best_val_loss = val_loss;
            best_epoch = epoch + 1;
            patience_counter = 0;
            save_weights(net, best_net);
            printf("  *best*");
        } else {
            patience_counter++;
        }
        printf("\n");

        if (patience_counter >= patience) {
            printf("\nEarly stopping at epoch %d (best epoch: %d)\n", epoch + 1, best_epoch);
            break;
        }
    }

    /* Restore best weights and save */
    save_weights(best_net, net);
    safetensor_save("autokem.safetensors", net, total, best_epoch, best_val_loss);

    /* Compute final per-bit accuracy on validation set */
    {
        const char *bit_names[] = {"A","B","C","D","E","F","G","H","J","K","Ytype","LowH"};
        int correct_per_bit[12] = {0};
        int total_per_bit = n_val;
        int n_examples = 0;
        const int max_examples = 8;

        printf("\nGlyph Tags — validation predictions:\n");

        for (int i = 0; i < n_val; i++) {
            Sample *s = &all_samples[indices[n_train + i]];
            float output[12];
            network_infer(net, s->input, output);

            int pred_bits[12], tgt_bits[12];
            int any_mismatch = 0;
            for (int b = 0; b < 10; b++) {
                pred_bits[b] = output[b] >= 0.5f ? 1 : 0;
                tgt_bits[b] = (int)s->shape[b];
                if (pred_bits[b] == tgt_bits[b]) correct_per_bit[b]++;
                else any_mismatch = 1;
            }
            pred_bits[10] = output[10] >= 0.5f ? 1 : 0;
            tgt_bits[10] = (int)s->ytype;
            if (pred_bits[10] == tgt_bits[10]) correct_per_bit[10]++;
            else any_mismatch = 1;
            pred_bits[11] = output[11] >= 0.5f ? 1 : 0;
            tgt_bits[11] = (int)s->lowheight;
            if (pred_bits[11] == tgt_bits[11]) correct_per_bit[11]++;
            else any_mismatch = 1;

            /* Print a few examples (mix of correct and mismatched) */
            if (n_examples < max_examples && (any_mismatch || i < 4)) {
                /* Build tag string: e.g. "ABCDEFGH(B)" or "AB(Y)" */
                char actual[32] = "", predicted[32] = "";
                int ap = 0, pp = 0;
                const char shape_chars[] = "ABCDEFGHJK";
                for (int b = 0; b < 10; b++) {
                    if (tgt_bits[b]) actual[ap++] = shape_chars[b];
                    if (pred_bits[b]) predicted[pp++] = shape_chars[b];
                }
                actual[ap] = '\0'; predicted[pp] = '\0';

                char actual_tag[48], pred_tag[48];
                snprintf(actual_tag, sizeof(actual_tag), "%s%s%s",
                         ap > 0 ? actual : "(empty)",
                         tgt_bits[10] ? "(Y)" : "(B)",
                         tgt_bits[11] ? " low" : "");
                snprintf(pred_tag, sizeof(pred_tag), "%s%s%s",
                         pp > 0 ? predicted : "(empty)",
                         pred_bits[10] ? "(Y)" : "(B)",
                         pred_bits[11] ? " low" : "");

                printf("  actual=%-20s pred=%-20s %s\n", actual_tag, pred_tag,
                       any_mismatch ? "MISMATCH" : "ok");
                n_examples++;
            }
        }

        printf("\nPer-bit accuracy (%d val samples):\n  ", n_val);
        int total_correct = 0;
        for (int b = 0; b < 12; b++) {
            printf("%s:%.1f%% ", bit_names[b], 100.0 * correct_per_bit[b] / total_per_bit);
            total_correct += correct_per_bit[b];
        }
        printf("\n  Overall: %d/%d (%.2f%%)\n",
               total_correct, n_val * 12, 100.0 * total_correct / (n_val * 12));
    }

    network_free(net);
    network_free(best_net);
    free(all_samples);
    free(indices);
    return 0;
}
