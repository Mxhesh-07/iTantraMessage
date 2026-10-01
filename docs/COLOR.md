# COLOR.md

The palette, and the contrast ratios computed from it.

Every number here was computed from the hex values in `app/src/main/res/values/colors.xml`
using the WCAG 2.x relative-luminance formula. None is quoted from a palette generator.

---

## 1. The palette

A Material 3 tonal palette generated from one indigo source colour.

Only the roles this app actually paints are defined. A full M3 baseline has 40+ colour roles;
the extras would be unused, and an unused resource is a thing a reviewer has to verify is
unused.

```xml
brand_primary        #1A4C8B     on_primary            #FFFFFF
primary_container    #D6E3FF     on_primary_container  #001B3C
surface              #FDFBFF     on_surface            #1A1C1E
surface_variant      #E0E2EC     on_surface_variant    #43474E
error                #BA1A1A     on_error              #FFFFFF
```

## 2. Measured contrast

WCAG relative luminance: `L = 0.2126R + 0.7152G + 0.0722B`, each channel linearised as
`c/12.92` below 0.03928 and `((c+0.055)/1.055)^2.4` above. Ratio is
`(lighter + 0.05) / (darker + 0.05)`.

| foreground | background | ratio | AA (4.5:1) | AAA (7:1) |
|---|---|---|---|---|
| `on_surface` #1A1C1E | `surface` #FDFBFF | **16.62:1** | pass | pass |
| `on_surface_variant` #43474E | `surface` #FDFBFF | **9.08:1** | pass | pass |
| `on_primary_container` #001B3C | `primary_container` #D6E3FF | **13.33:1** | pass | pass |
| `on_primary` #FFFFFF | `brand_primary` #1A4C8B | **8.57:1** | pass | pass |
| `primary` #1A4C8B | `surface` #FDFBFF | **8.33:1** | pass | pass |
| `on_error` #FFFFFF | `error` #BA1A1A | **6.46:1** | pass | — |

Every body-text pairing clears **AAA** except the error pair, which clears **AA** with margin.
AA needs 4.5:1 for body text and 3:1 for large text; AAA needs 7:1.

### 2.1 Recomputing

The check is a script, not a claim. To re-run it after changing a hex value:

```powershell
function Get-Lum([string]$hex) {
  $h = $hex.TrimStart('#')
  $r = [Convert]::ToInt32($h.Substring(0,2),16)/255.0
  $g = [Convert]::ToInt32($h.Substring(2,2),16)/255.0
  $b = [Convert]::ToInt32($h.Substring(4,2),16)/255.0
  function Chan($c) { if ($c -le 0.03928) { $c/12.92 } else { [Math]::Pow((($c+0.055)/1.055), 2.4) } }
  0.2126*(Chan $r) + 0.7152*(Chan $g) + 0.0722*(Chan $b)
}
function Get-Ratio([string]$a, [string]$b) {
  $l1 = Get-Lum $a; $l2 = Get-Lum $b
  if ($l1 -lt $l2) { $t=$l1; $l1=$l2; $l2=$t }
  [Math]::Round(($l1+0.05)/($l2+0.05), 2)
}
Get-Ratio '#1A1C1E' '#FDFBFF'   # 16.62
```

An earlier version of `colors.xml` carried a comment claiming ratios of 12.0, 8.6, 7.3 and
7.4. Computing them showed **all four were wrong** — the real values are 16.62, 8.57, 9.08 and
13.33. All four pairs passed either way, so the mistake was harmless in effect and still worth
fixing: a comment that states a measurement nobody made is worse than no comment, because the
next person checking it assumes it was checked.

## 3. Status colours

The delivery chips are the **only** place colour carries meaning.

```xml
status_pending    #6B5E00     6.33:1 on surface   AA
status_sent       #1A4C8B     8.33:1 on surface   AAA
status_delivered  #1E5B2E     7.88:1 on surface   AAA
status_failed     #BA1A1A     6.28:1 on surface   AA
```

All four clear AA against `surface`, so the label is legible in every state.

**Every chip also carries a text label.** Colour never carries state on its own. Roughly 1 in
12 men has a red-green colour vision deficiency, and `status_pending` #6B5E00 against
`status_failed` #BA1A1A is exactly the pair that distinction fails on. The label makes the
state readable regardless.

The two chips are not distinguished by hue alone either — yellow-brown and red differ in
lightness as well, so they remain separable in greyscale.

## 4. Dark theme

**NOT MEASURED** and **not implemented.**

`Theme.kt` defines a single light scheme. Dark theme would need its own tonal ramp, and the
contrast ratios above are light-theme numbers that do not transfer — a ratio measured on
`surface` #FDFBFF says nothing about the same pairing on a dark surface.

Stating this rather than shipping a dark scheme and reusing light numbers is the point: the
light numbers are measured, and a reader should be able to trust every number in this file.

Adding dark theme means generating the M3 dark tonal palette and running §2.1 over every pair.

## 5. Other notes

- `ic_launcher_foreground.xml` is a vector, not a bitmap, so it needs no density variants.
  Adaptive icons (`mipmap-anydpi-v26`) are used, with a `mipmap/` fallback for API < 26.
- No colour is defined in Kotlin. The single `error_undecrypt` string and friends live in
  `strings.xml`; all colour comes from `MaterialTheme.colorScheme`, so a theme change is one
  file.