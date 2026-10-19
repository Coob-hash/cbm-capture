# Camera intrinsics

This document specifies how the app obtains the camera's intrinsic matrix K, keeps it consistent
with the image it sends, and decides whether it can be trusted. `ImageTransform.kt` implements
section 2 and `IntrinsicsGate.kt` section 3; their tests assert the identities stated here.

## 1. Where K comes from

K is taken from the most trustworthy source the device offers. The choice is recorded in
`camera.source`, so every result can be traced back to how its camera was calibrated.

| Rank | `camera.source`   | API                                                   |
|------|-------------------|-------------------------------------------------------|
| 1    | `ARCORE`          | `Frame.getCamera().getImageIntrinsics()`              |
| 2    | `ANDROID_CAMERA2` | `CameraCharacteristics.LENS_INTRINSIC_CALIBRATION`    |
| 3    | `EXIF`            | `FocalLengthIn35mmFilm`                               |
| –    | `MANUAL_OVERRIDE` | Entered by hand; always sent with `trusted: false`    |

The capture schema also defines `ARKIT`, for iOS clients.

**ARCore** reports K already expressed in the coordinate system of the frame it hands over: no
rescaling is needed, and no assumption about sensor cropping is involved.

**Camera2** is harder to use correctly, not less accurate. `LENS_INTRINSIC_CALIBRATION` returns
`[fx, fy, cx, cy, s]` in the coordinate system of the *pre-correction active array*
(`SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`), which is generally neither the active array nor
the JPEG the app saves. Converting it requires:

```text
K_image = scale( crop( K_preCorrectionArray, SCALER_CROP_REGION ), outputSize )
```

`Camera2Calibration.kt` performs this composition. The key is optional in the Camera2
specification and many devices do not report it, which is why a further fallback exists.

**EXIF** is the last resort: the 35 mm-equivalent estimate `fx = f35 * W / 36`, `fy = fx`,
`cx = W / 2`, `cy = H / 2`. It is accurate to a few percent, which is enough to locate a door but
never preferred over a factory calibration.

When no source can supply K, the capture is refused rather than given an invented one. A K that
fails the plausibility gate (section 3) is sent with `trusted: false`.

## 2. Transforming K together with the pixels

The app rotates and downscales every frame before upload. Each transform is applied to the image,
to K and to the user's tap **in the same operation**, so the three can never drift apart:

```text
sensor frame --rotate(k quarter-turns CW)--> upright frame --resize(s)--> transmitted frame
```

### 2.1 Rotation by k quarter-turns clockwise

Let the input be `W x H` with intrinsics `(fx, fy, cx, cy)`, using continuous pixel coordinates
`(u, v)`.

| k | New size | Pixel mapping    | fx' | fy' | cx'      | cy'      |
|---|----------|------------------|-----|-----|----------|----------|
| 0 | `W x H`  | `(u, v)`         | fx  | fy  | cx       | cy       |
| 1 | `H x W`  | `(H - v, u)`     | fy  | fx  | `H - cy` | cx       |
| 2 | `W x H`  | `(W - u, H - v)` | fx  | fy  | `W - cx` | `H - cy` |
| 3 | `H x W`  | `(v, W - u)`     | fy  | fx  | cy       | `W - cx` |

**Derivation for k = 1.** The pinhole model gives `u = cx + fx*x` and `v = cy + fy*y` for a
normalised camera-space direction `(x, y, 1)`. Rotating the image 90 degrees clockwise sends
`(u, v)` to `(u', v') = (H - v, u)`. Substituting:

```text
u' = H - v = H - (cy + fy*y) = (H - cy) + fy*(-y)
v' = u     = cx + fx*x
```

Reading off the pinhole form in the rotated frame, `u' = cx' + fx'*x'` and `v' = cy' + fy'*y'`
with `(x', y') = (-y, x)`, gives `fx' = fy`, `cx' = H - cy`, `fy' = fx`, `cy' = cx`. The
substitution `(x, y) -> (-y, x)` is itself a rotation of the camera frame about the optical axis,
so the rotated K describes the rotated camera looking at the rotated image. k = 2 and k = 3 follow
in the same way.

**Why rotate the pixels** rather than store the sensor-native buffer with an EXIF orientation tag?
A tag is advisory: every consumer of the image is free to honour or ignore it, and any
disagreement silently rotates the ray by 90 degrees. Baking the rotation into the pixels, and
recording it as `orientation_applied`, leaves nothing to interpret.

### 2.2 Uniform downscale

The longest side is reduced to `MAX_UPLOAD_LONG_SIDE` (1280 px). The **realised** per-axis ratios
are used rather than the requested factor, because integer rounding of the output dimensions makes
them differ slightly:

```text
sx  = W_out / W_in       sy  = H_out / H_in
fx' = sx * fx            fy' = sy * fy
cx' = sx * cx            cy' = sy * cy
u'  = sx * u             v'  = sy * v
```

All four of `fx, fy, cx, cy` are linear in resolution, so all four scale, each by the factor of its
own axis. Scaling only `fx` and `fy`, or applying one factor to all four, is the classic version of
this bug.

### 2.3 The invariant

After the pipeline runs:

```text
camera.width  == image.width   == the JPEG's decoded width
camera.height == image.height  == the JPEG's decoded height
0 <= target.pixel.x < image.width
0 <= target.pixel.y < image.height
```

The app asserts this before a capture is written to the outbox, and the server checks it again on
receipt. It is cheap to verify and costly to get wrong.

## 3. Plausibility gate

The gate is evaluated on the **final**, transmitted-frame K:

```text
0.5 * W  <= fx <= 2.5 * W
0.5 * H  <= fy <= 2.5 * H
0.30 * W <= cx <= 0.70 * W
0.30 * H <= cy <= 0.70 * H
|fx - fy| / max(fx, fy) <= 0.05
```

An `fx` between `0.5 W` and `2.5 W` corresponds to a horizontal field of view between roughly 23
and 90 degrees, which brackets every phone lens from ultra-wide to 3x telephoto with margin. The
principal-point bounds catch a mismatched frame: a K that describes a 1920 x 1440 image attached to
a 4032 x 3024 photograph. Phone pixels are square to well within 5 %.

Failing the gate sets `trusted: false`. It never substitutes a guessed value.

## 4. Lens distortion and centrality

The ray computation assumes a pure pinhole model. Instead of undistorting the image, the app
computes

```text
centrality = max( |x/W - 0.5|, |y/H - 0.5| ) / 0.5
```

and asks the user to re-frame when `centrality > 0.6`, so that the defect sits nearer the centre.
On an ultra-wide lens, radial distortion near the edge of the frame reaches several pixels, which
is degrees of ray error that no correct K can compensate. The value is sent with the capture so the
server can apply the same rule.

## 5. Deliberately not done

- **Distortion coefficients are carried, not applied.** Applying a partial correction is worse than
  applying none.
- **Skew is transmitted but ignored.** Camera2 reports it; the ray computation uses only
  `fx, fy, cx, cy`.
- **The AR pose is advisory.** ARCore's camera pose lives in the AR session's own world frame,
  which has no relationship to the building model's coordinate system. It is transmitted only so
  that it can be compared offline with the pose the server establishes, and must never be used to
  cast the ray.
