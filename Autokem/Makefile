CC = gcc
CFLAGS = -Ofast -Wall -Wextra -std=c11
LDFLAGS = -lm
SRC = main.c tga.c nn.c safetensor.c train.c apply.c
OBJ = $(SRC:.c=.o)

all: autokem

autokem: $(OBJ)
	$(CC) $(CFLAGS) -o $@ $^ $(LDFLAGS)

%.o: %.c
	$(CC) $(CFLAGS) -c $< -o $@

debug: CFLAGS = -g -Wall -Wextra -std=c11 -fsanitize=address,undefined
debug: LDFLAGS += -fsanitize=address,undefined
debug: clean autokem

clean:
	rm -f *.o autokem

.PHONY: all debug clean
