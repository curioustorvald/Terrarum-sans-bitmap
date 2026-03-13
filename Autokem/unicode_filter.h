#ifndef UNICODE_FILTER_H
#define UNICODE_FILTER_H

#include <string.h>

/*
 * Unicode category filters for training/apply.
 * Generated from Python unicodedata (Unicode 16.0).
 *
 * is_modifier_letter(cp)         — category Lm
 * is_subscript_modifier(cp)      — Lm with <sub> decomposition
 * is_symbol_or_punctuation(cp)   — categories S* or P*
 * is_excluded_from_training(cp)  — Lm or S* or P*
 */

/* ---- Lm (modifier letter) ---- */

static inline int is_modifier_letter(int cp) {
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
    if (cp >= 0x1D62 && cp <= 0x1D6A) return 1;
    if (cp >= 0x2090 && cp <= 0x209C) return 1;
    if (cp == 0x2C7C) return 1;
    if (cp >= 0x1E051 && cp <= 0x1E06A) return 1;
    return 0;
}

/* ---- S* (Symbol) and P* (Punctuation) ---- */

/* Table of {start, end} ranges for S/P codepoints in font sheets */
static const int sp_ranges[][2] = {
    {0x00021, 0x0002F}, {0x0003A, 0x00040}, {0x0005B, 0x00060},
    {0x0007B, 0x0007E}, {0x000A1, 0x000A9}, {0x000AB, 0x000AC},
    {0x000AE, 0x000B1}, {0x000B4, 0x000B4}, {0x000B6, 0x000B8},
    {0x000BB, 0x000BB}, {0x000BF, 0x000BF}, {0x000D7, 0x000D7},
    {0x000F7, 0x000F7}, {0x002C2, 0x002C5}, {0x002D2, 0x002DF},
    {0x002E5, 0x002EB}, {0x002ED, 0x002ED}, {0x002EF, 0x002FF},
    {0x00375, 0x00375}, {0x0037E, 0x0037E}, {0x00384, 0x00385},
    {0x00387, 0x00387}, {0x00482, 0x00482}, {0x0055A, 0x0055F},
    {0x00589, 0x0058A}, {0x0058D, 0x0058F}, {0x00964, 0x00965},
    {0x00970, 0x00970}, {0x009F2, 0x009F3}, {0x009FA, 0x009FB},
    {0x009FD, 0x009FD}, {0x00BF3, 0x00BFA}, {0x00E3F, 0x00E3F},
    {0x00E4F, 0x00E4F}, {0x00E5A, 0x00E5B}, {0x010FB, 0x010FB},
    {0x016EB, 0x016ED}, {0x01CC0, 0x01CC7}, {0x01FBD, 0x01FBD},
    {0x01FBF, 0x01FC1}, {0x01FCD, 0x01FCF}, {0x01FDD, 0x01FDF},
    {0x01FED, 0x01FEF}, {0x01FFD, 0x01FFE}, {0x02010, 0x02027},
    {0x02030, 0x0205E}, {0x0207A, 0x0207E}, {0x0208A, 0x0208E},
    {0x020A0, 0x020C0}, {0x02100, 0x02101}, {0x02103, 0x02106},
    {0x02108, 0x02109}, {0x02114, 0x02114}, {0x02116, 0x02118},
    {0x0211E, 0x02123}, {0x02125, 0x02125}, {0x02127, 0x02127},
    {0x02129, 0x02129}, {0x0212E, 0x0212E}, {0x0213A, 0x0213B},
    {0x02140, 0x02144}, {0x0214A, 0x0214D}, {0x0214F, 0x0214F},
    {0x0218A, 0x0218B}, {0x02190, 0x021FF}, {0x02400, 0x02426},
    {0x02800, 0x028FF}, {0x03001, 0x03004}, {0x03008, 0x03020},
    {0x03030, 0x03030}, {0x03036, 0x03037}, {0x0303D, 0x0303F},
    {0x0309B, 0x0309C}, {0x030A0, 0x030A0}, {0x030FB, 0x030FB},
    {0x04DC0, 0x04DFF}, {0x0A673, 0x0A673}, {0x0A67E, 0x0A67E},
    {0x0A720, 0x0A721}, {0x0A789, 0x0A78A}, {0x0AB5B, 0x0AB5B},
    {0x0AB6A, 0x0AB6B}, {0x0FF01, 0x0FF0F}, {0x0FF1A, 0x0FF20},
    {0x0FF3B, 0x0FF40}, {0x0FF5B, 0x0FF65}, {0x0FFE0, 0x0FFE6},
    {0x0FFE8, 0x0FFEE}, {0x0FFFC, 0x0FFFD}, {0x1F10D, 0x1F1AD},
    {0x1F1E6, 0x1F1FF}, {0x1FB00, 0x1FB92}, {0x1FB94, 0x1FBCA},
};

static inline int is_symbol_or_punctuation(int cp) {
    int n = (int)(sizeof(sp_ranges) / sizeof(sp_ranges[0]));
    for (int i = 0; i < n; i++) {
        if (cp >= sp_ranges[i][0] && cp <= sp_ranges[i][1])
            return 1;
    }
    return 0;
}

/* ---- Combined filter for training exclusion ---- */

static inline int is_excluded_from_training(int cp) {
    return is_modifier_letter(cp) || is_symbol_or_punctuation(cp);
}

/* ---- Sheet filename → start codepoint ---- */

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

#endif /* UNICODE_FILTER_H */
