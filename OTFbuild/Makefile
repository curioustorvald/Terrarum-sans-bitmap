PYTHON   ?= python3
ASSETS   ?= ../src/assets
OTF      = TerrarumSansBitmap.otf
WOFF2    = TerrarumSansBitmap.woff2

all: $(OTF) $(WOFF2)

$(OTF): $(wildcard $(ASSETS)/*.tga) build_font.py font_builder.py glyph_parser.py \
        bitmap_tracer.py tga_reader.py keming_machine.py hangul.py sheet_config.py \
        opentype_features.py
	$(PYTHON) build_font.py $(ASSETS) -o $@

$(WOFF2): $(OTF) otf2woff2.py
	$(PYTHON) otf2woff2.py $< $@

clean:
	rm -f $(OTF) $(WOFF2)

.PHONY: all clean
