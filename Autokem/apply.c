#include "apply.h"
#include "tga.h"
#include "nn.h"
#include "safetensor.h"
#include "unicode_lm.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Copy file for backup */
static int copy_file(const char *src, const char *dst) {
    FILE *in = fopen(src, "rb");
    if (!in) return -1;

    FILE *out = fopen(dst, "wb");
    if (!out) { fclose(in); return -1; }

    char buf[4096];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), in)) > 0) {
        if (fwrite(buf, 1, n, out) != n) {
            fclose(in); fclose(out);
            return -1;
        }
    }

    fclose(in);
    fclose(out);
    return 0;
}

int apply_model(const char *tga_path) {
    /* Validate filename */
    const char *basename = strrchr(tga_path, '/');
    basename = basename ? basename + 1 : tga_path;

    if (strstr(basename, "variable") == NULL) {
        fprintf(stderr, "Error: %s does not appear to be a variable sheet\n", tga_path);
        return 1;
    }
    if (strstr(basename, "extrawide") != NULL) {
        fprintf(stderr, "Error: extrawide sheets are not supported\n");
        return 1;
    }

    int is_xyswap = (strstr(basename, "xyswap") != NULL);

    /* Create backup */
    char bakpath[512];
    snprintf(bakpath, sizeof(bakpath), "%s.bak", tga_path);
    if (copy_file(tga_path, bakpath) != 0) {
        fprintf(stderr, "Error: failed to create backup %s\n", bakpath);
        return 1;
    }
    printf("Backup: %s\n", bakpath);

    /* Load model */
    Network *net = network_create();
    if (safetensor_load("autokem.safetensors", net) != 0) {
        fprintf(stderr, "Error: failed to load model\n");
        network_free(net);
        return 1;
    }

    /* Load TGA */
    TgaImage *img = tga_read(tga_path);
    if (!img) {
        fprintf(stderr, "Error: cannot read %s\n", tga_path);
        network_free(net);
        return 1;
    }

    int cell_w = 16, cell_h = 20;
    int cols = img->width / cell_w;
    int rows = img->height / cell_h;
    int total_cells = cols * rows;

    int start_code = sheet_start_code(basename);
    int processed = 0, updated = 0, skipped = 0, fixed_lm = 0;

    for (int index = 0; index < total_cells; index++) {
        int cell_x, cell_y;
        if (is_xyswap) {
            cell_x = (index / cols) * cell_w;
            cell_y = (index % cols) * cell_h;
        } else {
            cell_x = (index % cols) * cell_w;
            cell_y = (index / cols) * cell_h;
        }

        int tag_x = cell_x + (cell_w - 1);
        int tag_y = cell_y;

        /* Read width */
        int width = 0;
        for (int y = 0; y < 5; y++) {
            if (tga_get_pixel(img, tag_x, tag_y + y) & 0xFF)
                width |= (1 << y);
        }
        if (width == 0) { skipped++; continue; }

        /* Check writeOnTop at Y+17 — skip if defined */
        uint32_t wot = tga_get_pixel(img, tag_x, tag_y + 17);
        if ((wot & 0xFF) != 0) { skipped++; continue; }

        /* Check compiler directive at Y+9 — skip if opcode != 0 */
        uint32_t dir_pixel = tagify(tga_get_pixel(img, tag_x, tag_y + 9));
        int opcode = (int)((dir_pixel >> 24) & 0xFF);
        if (opcode != 0) { skipped++; continue; }

        /* Modifier letters: fixed kern pixel, skip inference */
        if (start_code >= 0 && is_modifier_letter(start_code + index)) {
            if (is_subscript_modifier(start_code + index)) {
                /* Subscript: CDEFGHJK(B), lowheight=1 */
                tga_write_pixel(tga_path, img, tag_x, tag_y + 5, 0xFFFFFFFF);
                tga_write_pixel(tga_path, img, tag_x, tag_y + 6, 0x00C03FFF);
            } else {
                /* Superscript: ABCDEF(B), lowheight=0 */
                tga_write_pixel(tga_path, img, tag_x, tag_y + 5, 0x00000000);
                tga_write_pixel(tga_path, img, tag_x, tag_y + 6, 0x0000FCFF);
            }
            processed++; updated++; fixed_lm++;
            continue;
        }

        /* Extract 15x20 binary input */
        float input[300];
        for (int gy = 0; gy < 20; gy++) {
            for (int gx = 0; gx < 15; gx++) {
                uint32_t p = tga_get_pixel(img, cell_x + gx, cell_y + gy);
                input[gy * 15 + gx] = ((p & 0x80) != 0) ? 1.0f : 0.0f;
            }
        }

        /* Inference */
        float output[12];
        network_infer(net, input, output);

        /* Threshold at 0.5 */
        int A = output[0] >= 0.5f;
        int B = output[1] >= 0.5f;
        int C = output[2] >= 0.5f;
        int D = output[3] >= 0.5f;
        int E = output[4] >= 0.5f;
        int F = output[5] >= 0.5f;
        int G = output[6] >= 0.5f;
        int H = output[7] >= 0.5f;
        int J = output[8] >= 0.5f;
        int K = output[9] >= 0.5f;
        int ytype = output[10] >= 0.5f;
        int lowheight = output[11] >= 0.5f;

        /* Compose Y+5 pixel: lowheight (alpha=0xFF when set) */
        uint32_t lh_pixel = lowheight ? 0xFFFFFFFF : 0x00000000;
        tga_write_pixel(tga_path, img, tag_x, tag_y + 5, lh_pixel);

        /* Compose Y+6 pixel:
         * Red byte: Y0000000 -> bit 31
         * Green byte: JK000000 -> bits 23,22
         * Blue byte: ABCDEFGH -> bits 15-8
         * Alpha: 0xFF = hasKernData */
        uint32_t pixel = 0;
        pixel |= (uint32_t)(ytype ? 0x80 : 0) << 24;
        pixel |= (uint32_t)((J ? 0x80 : 0) | (K ? 0x40 : 0)) << 16;
        pixel |= (uint32_t)(A<<7 | B<<6 | C<<5 | D<<4 | E<<3 | F<<2 | G<<1 | H) << 8;
        pixel |= 0xFF;

        tga_write_pixel(tga_path, img, tag_x, tag_y + 6, pixel);

        processed++;
        updated++;
    }

    printf("Processed: %d cells, Updated: %d, Skipped: %d, Fixed Lm: %d (of %d total)\n",
           processed, updated, skipped, fixed_lm, total_cells);

    tga_free(img);
    network_free(net);
    return 0;
}
