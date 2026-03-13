#ifndef UNICODE_LM_H
#define UNICODE_LM_H

#include <string.h>

/*
 * Unicode category Lm (Letter, modifier) range checks.
 * Generated from Python unicodedata (Unicode 16.0).
 *
 * is_modifier_letter(cp)    — true for all Lm codepoints
 * is_subscript_modifier(cp) — true for Lm codepoints with <sub> decomposition
 */

static inline int is_modifier_letter(int cp) {
    /* 71 contiguous ranges covering all 397 Lm codepoints */
    if (cp >= 0x02B0 && cp <= 0x02C1) return 1;
    if (cp >= 0x02C6 && cp <= 0x02D1) return 1;
    if (cp >= 0x02E0 && cp <= 0x02E4) return 1;
    if (cp == 0x02EC) return 1;
    if (cp == 0x02EE) return 1;
    if (cp == 0x0374) return 1;
    if (cp == 0x037A) return 1;
    if (cp == 0x0559) return 1;
    if (cp == 0x0640) return 1;
    if (cp >= 0x06E5 && cp <= 0x06E6) return 1;
    if (cp >= 0x07F4 && cp <= 0x07F5) return 1;
    if (cp == 0x07FA) return 1;
    if (cp == 0x081A) return 1;
    if (cp == 0x0824) return 1;
    if (cp == 0x0828) return 1;
    if (cp == 0x08C9) return 1;
    if (cp == 0x0971) return 1;
    if (cp == 0x0E46) return 1;
    if (cp == 0x0EC6) return 1;
    if (cp == 0x10FC) return 1;
    if (cp == 0x17D7) return 1;
    if (cp == 0x1843) return 1;
    if (cp == 0x1AA7) return 1;
    if (cp >= 0x1C78 && cp <= 0x1C7D) return 1;
    if (cp >= 0x1D2C && cp <= 0x1D6A) return 1;
    if (cp == 0x1D78) return 1;
    if (cp >= 0x1D9B && cp <= 0x1DBF) return 1;
    if (cp == 0x2071) return 1;
    if (cp == 0x207F) return 1;
    if (cp >= 0x2090 && cp <= 0x209C) return 1;
    if (cp >= 0x2C7C && cp <= 0x2C7D) return 1;
    if (cp == 0x2D6F) return 1;
    if (cp == 0x2E2F) return 1;
    if (cp == 0x3005) return 1;
    if (cp >= 0x3031 && cp <= 0x3035) return 1;
    if (cp == 0x303B) return 1;
    if (cp >= 0x309D && cp <= 0x309E) return 1;
    if (cp >= 0x30FC && cp <= 0x30FE) return 1;
    if (cp == 0xA015) return 1;
    if (cp >= 0xA4F8 && cp <= 0xA4FD) return 1;
    if (cp == 0xA60C) return 1;
    if (cp == 0xA67F) return 1;
    if (cp >= 0xA69C && cp <= 0xA69D) return 1;
    if (cp >= 0xA717 && cp <= 0xA71F) return 1;
    if (cp == 0xA770) return 1;
    if (cp == 0xA788) return 1;
    if (cp >= 0xA7F2 && cp <= 0xA7F4) return 1;
    if (cp >= 0xA7F8 && cp <= 0xA7F9) return 1;
    if (cp == 0xA9CF) return 1;
    if (cp == 0xA9E6) return 1;
    if (cp == 0xAA70) return 1;
    if (cp == 0xAADD) return 1;
    if (cp >= 0xAAF3 && cp <= 0xAAF4) return 1;
    if (cp >= 0xAB5C && cp <= 0xAB5F) return 1;
    if (cp == 0xAB69) return 1;
    if (cp == 0xFF70) return 1;
    if (cp >= 0xFF9E && cp <= 0xFF9F) return 1;
    if (cp >= 0x10780 && cp <= 0x10785) return 1;
    if (cp >= 0x10787 && cp <= 0x107B0) return 1;
    if (cp >= 0x107B2 && cp <= 0x107BA) return 1;
    if (cp >= 0x16B40 && cp <= 0x16B43) return 1;
    if (cp >= 0x16F93 && cp <= 0x16F9F) return 1;
    if (cp >= 0x16FE0 && cp <= 0x16FE1) return 1;
    if (cp == 0x16FE3) return 1;
    if (cp >= 0x1AFF0 && cp <= 0x1AFF3) return 1;
    if (cp >= 0x1AFF5 && cp <= 0x1AFFB) return 1;
    if (cp >= 0x1AFFD && cp <= 0x1AFFE) return 1;
    if (cp >= 0x1E030 && cp <= 0x1E06D) return 1;
    if (cp >= 0x1E137 && cp <= 0x1E13D) return 1;
    if (cp == 0x1E4EB) return 1;
    if (cp == 0x1E94B) return 1;
    return 0;
}

static inline int is_subscript_modifier(int cp) {
    /* 49 Lm codepoints with <sub> decomposition */
    if (cp >= 0x1D62 && cp <= 0x1D6A) return 1;  /* 9 */
    if (cp >= 0x2090 && cp <= 0x209C) return 1;   /* 13 */
    if (cp == 0x2C7C) return 1;                    /* 1 */
    if (cp >= 0x1E051 && cp <= 0x1E06A) return 1;  /* 26 */
    return 0;
}

/*
 * Map sheet filename to first codepoint of its (contiguous) code range.
 * Returns -1 if unknown. For non-contiguous sheets (e.g. Devanagari),
 * returns the start of the first sub-range; cells beyond it won't
 * collide with Lm codepoints in practice.
 */
static int sheet_start_code(const char *basename) {
    if (strstr(basename, "ascii_variable"))                return 0x00;
    if (strstr(basename, "latinExtA_variable"))            return 0x100;
    if (strstr(basename, "latinExtB_variable"))            return 0x180;
    if (strstr(basename, "cyrilic_extC_variable"))         return 0x1C80;
    if (strstr(basename, "cyrilic_extB_variable"))         return 0xA640;
    if (strstr(basename, "cyrilic_bulgarian_variable"))    return 0xF0000;
    if (strstr(basename, "cyrilic_serbian_variable"))      return 0xF0060;
    if (strstr(basename, "cyrilic_variable"))              return 0x400;
    if (strstr(basename, "halfwidth_fullwidth_variable"))  return 0xFF00;
    if (strstr(basename, "unipunct_variable"))             return 0x2000;
    if (strstr(basename, "greek_polytonic"))               return 0x1F00;
    if (strstr(basename, "greek_variable"))                return 0x370;
    if (strstr(basename, "thai_variable"))                 return 0xE00;
    if (strstr(basename, "hayeren_variable"))              return 0x530;
    if (strstr(basename, "kartuli_allcaps_variable"))      return 0x1C90;
    if (strstr(basename, "kartuli_variable"))              return 0x10D0;
    if (strstr(basename, "ipa_ext_variable"))              return 0x250;
    if (strstr(basename, "latinExt_additional_variable"))  return 0x1E00;
    if (strstr(basename, "tsalagi_variable"))              return 0x13A0;
    if (strstr(basename, "phonetic_extensions_variable"))  return 0x1D00;
    if (strstr(basename, "latinExtC_variable"))            return 0x2C60;
    if (strstr(basename, "latinExtD_variable"))            return 0xA720;
    if (strstr(basename, "internal_variable"))             return 0xFFE00;
    if (strstr(basename, "letterlike_symbols_variable"))   return 0x2100;
    if (strstr(basename, "enclosed_alphanumeric"))         return 0x1F100;
    if (strstr(basename, "sundanese_variable"))            return 0x1B80;
    if (strstr(basename, "control_pictures_variable"))     return 0x2400;
    if (strstr(basename, "latinExtE_variable"))            return 0xAB30;
    if (strstr(basename, "latinExtF_variable"))            return 0x10780;
    if (strstr(basename, "latinExtG_variable"))            return 0x1DF00;
    if (strstr(basename, "devanagari") && !strstr(basename, "internal"))
                                                           return 0x900;
    return -1;
}

#endif /* UNICODE_LM_H */
