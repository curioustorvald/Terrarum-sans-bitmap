#include <stdio.h>
#include <string.h>
#include "train.h"
#include "apply.h"
#include "safetensor.h"

static void print_usage(void) {
    printf("Usage: autokem <command> [args]\n");
    printf("Commands:\n");
    printf("  train              Train model on existing spritesheets\n");
    printf("  apply <file.tga>   Apply trained model to a spritesheet\n");
    printf("  stats              Print model statistics\n");
    printf("  help               Print this message\n");
}

int main(int argc, char **argv) {
    if (argc < 2) {
        print_usage();
        return 1;
    }

    if (strcmp(argv[1], "train") == 0) {
        return train_model();
    } else if (strcmp(argv[1], "apply") == 0) {
        if (argc < 3) {
            fprintf(stderr, "Error: apply requires a TGA file path\n");
            return 1;
        }
        return apply_model(argv[2]);
    } else if (strcmp(argv[1], "stats") == 0) {
        return safetensor_stats("autokem.safetensors");
    } else if (strcmp(argv[1], "help") == 0) {
        print_usage();
        return 0;
    } else {
        fprintf(stderr, "Unknown command: %s\n", argv[1]);
        print_usage();
        return 1;
    }
}
