# Intrinsics: sourcing, transformation, and the plausibility gate

This is the normative specification for the camera-calibration logic. `ImageTransform.swift`
and `ImageTransform.kt` are line-for-line implementations of section 2, and both test suites
assert the identities stated here. If the two ever disagree, this document is the referee.

---

## 1. Where K comes from

K is sourced from the highest-trust provider the device can offer, and the choice is recorded
in `camera.source` so that a resolved GlobalId can always be audited by how its camera was
calibrated.

| Rank | `camera.source` | Platform | API |
|------|-----------------|----------|-----|
| 1 | `ARKIT` | iOS | `ARFrame.camera.intrinsics` (3x3), with `ARFrame.camera.imageResolution` |
| 1 | `ARCORE` | Android | `Frame.camera.getImageIntrinsics()` |
| 2 | `ANDROID_CAMERA2` | Android | `CameraCharacteristics.LENS_INTRINSIC_CALIBRATION` |
| 3 | `EXIF` | both | `FocalLengthIn35mmFilm`, per section 4 of the calibration note |
| 4 | `MANUAL_OVERRIDE` | both | Settings screen, debug builds only, always `trusted: false` |

**ARKit and ARCore are rank 1 for the same reason:** both report K already expressed in the
coordinate system of the frame they hand you. No rescaling is needed, and no assumption about
sensor cropping is involved.

**Camera2 is rank 2 because it is harder to use correctly, not because it is less accurate.**
`LENS_INTRINSIC_CALIBRATION` returns `[fx, fy, cx, cy, s]` in the coordinate system of the
*pre-correction active array* (`SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`), which is
generally neither the active array nor the JPEG you save. Converting it requires:

```
K_image = scale( crop( K_preCorrectionArray, SCALER_CROP_REGION ), outputSize )
```

`Camera2IntrinsicsReader.kt` performs exactly that composition. It is also optional in the
Camera2 spec: many shipping devices return `null`, which is why a fallback below it exists.

**EXIF is rank 3 and always the last resort** before giving up. It is the section-4 estimate
from the calibration note: `fx = f35 * W / 36`, `fy = fx`, `cx = W/2`, `cy = H/2`. Good to a
few percent, which is enough to hit a door but not enough to be preferred when a factory
calibration is available.

If every provider fails, the app does **not** invent a K. It marks `trusted: false` and lets
the server route to `NEEDS_LOCALIZATION`. This is the single most important behavioural
difference from the current `$env.CAMERA_FX || 1200` fallback.

---

## 2. Transforming K alongside the pixels

The app rotates and downscales every frame before upload. Each transform is applied to the
image, to K, and to the worker's tap **in the same operation**, so the three can never drift
apart. The pipeline is fixed:

```
sensor frame  --rotate(k quarter-turns CW)-->  upright frame  --resize(s)-->  transmitted frame
```

### 2.1 Rotation by k quarter-turns clockwise

Let the input be `W x H` with intrinsics `(fx, fy, cx, cy)`, using continuous pixel
coordinates `(u, v)`.

| k | New size | Pixel mapping | fx' | fy' | cx' | cy' |
|---|----------|---------------|-----|-----|-----|-----|
| 0 | `W x H` | `(u, v)` | `fx` | `fy` | `cx` | `cy` |
| 1 | `H x W` | `(H - v, u)` | `fy` | `fx` | `H - cy` | `cx` |
| 2 | `W x H` | `(W - u, H - v)` | `fx` | `fy` | `W - cx` | `H - cy` |
| 3 | `H x W` | `(v, W - u)` | `fy` | `fx` | `cy` | `W - cx` |

**Derivation for k = 1.** The pinhole model gives `u = cx + fx*x` and `v = cy + fy*y` for a
normalised camera-space direction `(x, y, 1)`. Rotating the image 90 degrees clockwise sends
`(u, v)` to `(u', v') = (H - v, u)`. Substituting:

```
u' = H - v = H - (cy + fy*y) = (H - cy) + fy*(-y)
v' = u     = cx + fx*x
```

Reading off the pinhole form in the rotated frame, `u' = cx' + fx'*x'` and `v' = cy' + fy'*y'`
with `(x', y') = (-y, x)`, gives `fx' = fy`, `cx' = H - cy`, `fy' = fx`, `cy' = cx`. The
substitution `(x, y) -> (-y, x)` is itself a proper rotation of the camera frame about the
optical axis, so the model stays self-consistent: the rotated K describes the rotated camera
looking at the rotated image. k = 2 and k = 3 follow identically.

**Why rotate the pixels at all**, rather than storing the sensor-native landscape buffer and
setting an EXIF orientation tag? Because a tag is advisory. The vision model, MultiSet, the
FM's email client, and the IFC ray math would each be free to honour or ignore it, and any
disagreement silently rotates the ray by 90 degrees - precisely the fail-open class of defect
this whole exercise is meant to remove. Baking the rotation in and writing
`orientation_applied` as provenance leaves nothing to interpret.

### 2.2 Uniform downscale

Target the longest side at `MAX_UPLOAD_LONG_SIDE` (1280 px, per the 2026_07_08 package advice
for MultiSet). Use the **realised** per-axis ratios rather than the requested factor, because
integer rounding of the output dimensions makes them differ slightly:

```
sx = W_out / W_in          sy = H_out / H_in
fx' = sx * fx              fy' = sy * fy
cx' = sx * cx              cy' = sy * cy
u'  = sx * u               v'  = sy * v
```

Each of `fx, fy, cx, cy` is linear in resolution, so all four scale, and they scale by the
axis they belong to. Scaling only `fx`/`fy` - or applying one factor to all four - is the
classic version of this bug.

### 2.3 The invariant, stated once

After the pipeline runs:

```
camera.width  == image.width   == the JPEG's actual decoded width
camera.height == image.height  == the JPEG's actual decoded height
0 <= target.pixel.x < image.width,  0 <= target.pixel.y < image.height
```

Both apps assert this before an outbox record is written, and the server re-checks it on
receipt. It is cheap to verify and catastrophic to get wrong.

---

## 3. The plausibility gate

Mirrors the gate proposed in section 5.1 of the calibration note, evaluated on the **final**
transmitted-frame K:

```
0.5 * W  <=  fx  <=  2.5 * W
0.5 * H  <=  fy  <=  2.5 * H          (H used, since fy belongs to the vertical axis)
0.30 * W <=  cx  <=  0.70 * W
0.30 * H <=  cy  <=  0.70 * H
|fx - fy| / max(fx, fy)  <=  0.05     (phone pixels are square to well within 5%)
```

`fx` in the range `[0.5W, 2.5W]` corresponds to a horizontal field of view between roughly
23 and 90 degrees, which brackets every phone lens from ultra-wide to 3x telephoto with room
to spare. The principal-point bounds catch a mismatched frame - the failure mode where K
describes a 1920x1440 image and the pixels are 4032x3024 - which is exactly the live defect
identified in section 3 of the calibration note.

Failing the gate sets `trusted: false`. It does **not** substitute a guess.

---

## 4. Lens distortion and the centrality rule

`/elements/resolve-ray` assumes a pure pinhole model. Rather than implement undistortion in
v1, the app applies the low-cost mitigation from section 6.2 of the note: it computes

```
centrality = max( |x/W - 0.5|, |y/H - 0.5| ) / 0.5
```

and warns the worker when `centrality > 0.6`, asking them to re-frame so the defect sits
nearer the centre. On the ultra-wide lens, radial distortion at the frame edge reaches several
pixels - degrees of ray error - and no amount of correct K compensates for a model that does
not include it. The value is transmitted so the server can enforce the same rule if it chooses.

---

## 5. What is deliberately *not* done

- **Distortion coefficients are carried but not applied.** ARKit does not expose them for the
  captured image, and applying a partial correction is worse than applying none.
- **`skew` is transmitted but ignored.** Camera2 reports it; the ray math uses only
  `fx, fy, cx, cy`. It is recorded so a future undistortion step has the full matrix.
- **The AR world pose is advisory.** ARKit's `camera.transform` lives in the AR session's own
  world frame, which has no relationship to the MultiSet map or `T_VPS_TO_IFC`. Feeding it to
  the ray resolver would produce a confidently wrong GlobalId. WF2 continues to derive the
  trusted pose from MultiSet VPS; the AR pose is transmitted only so the two can be compared
  offline, which is the cross-check sketched in section 7 of the calibration note.
