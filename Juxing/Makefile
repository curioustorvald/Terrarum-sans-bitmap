PYTHON ?= python3
ASSETS ?= ../src/assets

MODEL   = model/han_model.tsv
SOURCES = sources/Unihan.zip sources/IDS.TXT sources/EquivalentUnifiedIdeograph.txt sources/CJKRadicals.txt \
          sources/CNS_Properties.zip sources/CNS_MapingTables.zip
OUTPUT  = out/juxing.tga

# assemble the Han sheet from whatever glyphlettes are drawn
all: $(OUTPUT)

$(SOURCES):
	$(PYTHON) juxing.py fetch

# the model is committed, so assembling needs no downloads; after editing
# model/overrides.tsv, rebuild it with `make model`
model: $(SOURCES)
	$(PYTHON) juxing.py build-model

fetch:
	$(PYTHON) juxing.py fetch

# measure the parts of every character in Chiron Hei HK (a 4th source; committed, like the model)
# and infer the layout preferences from them (committed too)
reference:
	$(PYTHON) juxing.py reference
	$(PYTHON) juxing.py infer

infer:
	$(PYTHON) juxing.py infer

plan:
	$(PYTHON) juxing.py plan

check:
	$(PYTHON) juxing.py check

status:
	$(PYTHON) juxing.py status

refresh:
	$(PYTHON) juxing.py refresh

$(OUTPUT): $(MODEL) model/layout.tsv model/reference.tsv model/inferred.tsv \
           $(wildcard glyphlettes/registry.tsv glyphlettes/sheet_*.tga) \
           juxing.py model.py ids.py geometry.py reference.py infer.py planner.py generators.py glyphlettes.py resize.py \
           assembler.py evenness.py tga.py
	$(PYTHON) juxing.py assemble

# web app: status at a glance and a pixel editor for the drawing sheets
serve:
	$(PYTHON) juxing.py serve

install: $(OUTPUT)
	cp $(OUTPUT) $(ASSETS)/

clean:
	rm -rf out

.PHONY: all model fetch reference infer plan check status refresh serve install clean
