#ifndef SAFETENSOR_H
#define SAFETENSOR_H

#include "nn.h"

/* Save network weights to .safetensors format.
   metadata: optional string pairs (key,value,...,NULL) */
int safetensor_save(const char *path, Network *net, int total_samples, int epochs, float val_loss);

/* Load network weights from .safetensors file. */
int safetensor_load(const char *path, Network *net);

/* Print model stats from .safetensors file. */
int safetensor_stats(const char *path);

#endif
