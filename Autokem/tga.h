#ifndef TGA_H
#define TGA_H

#include <stdint.h>
#include <stdio.h>

typedef struct {
    int width;
    int height;
    uint32_t *pixels;       /* RGBA8888: R<<24 | G<<16 | B<<8 | A */
    long pixel_data_offset; /* byte offset of pixel data in file */
    int top_to_bottom;
} TgaImage;

/* Read an uncompressed 32-bit TGA file. Returns NULL on error. */
TgaImage *tga_read(const char *path);

/* Get pixel at (x,y) as RGBA8888. Returns 0 for out-of-bounds. */
uint32_t tga_get_pixel(const TgaImage *img, int x, int y);

/* Write a single pixel (RGBA8888) to TGA file on disk at (x,y).
   Opens/closes the file internally. */
int tga_write_pixel(const char *path, TgaImage *img, int x, int y, uint32_t rgba);

/* Free a TgaImage. */
void tga_free(TgaImage *img);

/* tagify: returns 0 if alpha==0, else full pixel value */
static inline uint32_t tagify(uint32_t pixel) {
    return (pixel & 0xFF) == 0 ? 0 : pixel;
}

#endif
