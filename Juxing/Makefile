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

plan:
	$(PYTHON) juxing.py plan

check:
	$(PYTHON) juxing.py check

status:
	$(PYTHON) juxing.py status

refresh:
	$(PYTHON) juxing.py refresh

$(OUTPUT): $(MODEL) model/layout.tsv $(wildcard glyphlettes/registry.tsv glyphlettes/sheet_*.tga) \
           juxing.py model.py ids.py geometry.py planner.py generators.py glyphlettes.py assembler.py evenness.py tga.py
	$(PYTHON) juxing.py assemble

install: $(OUTPUT)
	cp $(OUTPUT) $(ASSETS)/

clean:
	rm -rf out

.PHONY: all model fetch plan check status refresh install clean
