#include "tga.h"
#include <stdlib.h>
#include <string.h>

TgaImage *tga_read(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;

    uint8_t header[18];
    if (fread(header, 1, 18, f) != 18) { fclose(f); return NULL; }

    uint8_t id_length = header[0];
    uint8_t colour_map_type = header[1];
    uint8_t image_type = header[2];
    /* skip colour map spec (bytes 3-7) */
    /* image spec starts at byte 8 */
    uint16_t width  = header[12] | (header[13] << 8);
    uint16_t height = header[14] | (header[15] << 8);
    uint8_t bpp = header[16];
    uint8_t descriptor = header[17];

    if (colour_map_type != 0 || image_type != 2 || bpp != 32) {
        fclose(f);
        return NULL;
    }

    int top_to_bottom = (descriptor & 0x20) != 0;

    /* skip image ID */
    if (id_length > 0) fseek(f, id_length, SEEK_CUR);

    long pixel_data_offset = 18 + id_length;

    TgaImage *img = malloc(sizeof(TgaImage));
    if (!img) { fclose(f); return NULL; }

    img->width = width;
    img->height = height;
    img->pixel_data_offset = pixel_data_offset;
    img->top_to_bottom = top_to_bottom;
    img->pixels = malloc((size_t)width * height * sizeof(uint32_t));
    if (!img->pixels) { free(img); fclose(f); return NULL; }

    for (int row = 0; row < height; row++) {
        int y = top_to_bottom ? row : (height - 1 - row);
        for (int x = 0; x < width; x++) {
            uint8_t bgra[4];
            if (fread(bgra, 1, 4, f) != 4) {
                free(img->pixels); free(img); fclose(f);
                return NULL;
            }
            /* TGA stores BGRA, convert to RGBA8888 */
            uint32_t r = bgra[2], g = bgra[1], b = bgra[0], a = bgra[3];
            img->pixels[y * width + x] = (r << 24) | (g << 16) | (b << 8) | a;
        }
    }

    fclose(f);
    return img;
}

uint32_t tga_get_pixel(const TgaImage *img, int x, int y) {
    if (x < 0 || x >= img->width || y < 0 || y >= img->height) return 0;
    return img->pixels[y * img->width + x];
}

int tga_write_pixel(const char *path, TgaImage *img, int x, int y, uint32_t rgba) {
    if (x < 0 || x >= img->width || y < 0 || y >= img->height) return -1;

    /* compute file row: reverse the mapping used during read */
    int file_row;
    if (img->top_to_bottom) {
        file_row = y;
    } else {
        file_row = img->height - 1 - y;
    }

    long offset = img->pixel_data_offset + ((long)file_row * img->width + x) * 4;

    FILE *f = fopen(path, "r+b");
    if (!f) return -1;

    fseek(f, offset, SEEK_SET);

    /* convert RGBA8888 to TGA BGRA */
    uint8_t bgra[4];
    bgra[2] = (rgba >> 24) & 0xFF; /* R */
    bgra[1] = (rgba >> 16) & 0xFF; /* G */
    bgra[0] = (rgba >> 8)  & 0xFF; /* B */
    bgra[3] = rgba & 0xFF;         /* A */

    size_t written = fwrite(bgra, 1, 4, f);
    fclose(f);

    /* also update in-memory pixel array */
    img->pixels[y * img->width + x] = rgba;

    return (written == 4) ? 0 : -1;
}

void tga_free(TgaImage *img) {
    if (!img) return;
    free(img->pixels);
    free(img);
}
