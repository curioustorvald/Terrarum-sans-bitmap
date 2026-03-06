#ifndef APPLY_H
#define APPLY_H

/* Apply trained model to a spritesheet.
   Creates .bak backup, then writes predicted kerning bits. */
int apply_model(const char *tga_path);

#endif
