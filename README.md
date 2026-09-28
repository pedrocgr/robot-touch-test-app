# Robot Touch Test

An Android application for repeatable touch-accuracy experiments. Every spawn displays one or more complete red, green, and blue target trios at the same time.

The app opens directly in a fixed-position test by default. Use **Settings** to choose the number of RGB trios, switch between fixed and random positions, independently enable random sizes, and set the radius range.

## Run

1. Open this directory in Android Studio.
2. Install Android SDK API 36 if prompted.
3. Connect an Android device with USB debugging enabled.
4. Select **Run**.

From a terminal with the Android SDK configured:

```bash
./gradlew installDebug
```

## Saved data

Each touch attempt is synchronously appended as one JSON object per line to:

```text
<app internal files directory>/touch_results.jsonl
```

On a typical device the full path is `/data/user/0/com.pcgr.robottouchtest/files/touch_results.jsonl`. Android protects this app-private directory. Use **Settings → Results and Data** to export the records as a JSON array or CSV through Android's document picker.

Each record contains:

- `event_id`, `session_id`, `test_id`, `date_time`, and `test_mode`
- `screen_width` and `screen_height` in pixels
- `target_id`, center coordinates, radius in pixels and dp, diameter in pixels, and target color
- actual touch coordinates
- `inside_target`, `result`, `distance_to_center`, `response_time_ms`, and `timed_out`
- `previous_attempts_in_session`
- `configuration`, a complete nested snapshot of the settings used for the test

Files exported by the JSON action can be imported again. Existing records with the same `event_id` are not duplicated. Deleting sessions and clearing all data require confirmation.

## Built-in validation check

At startup, a small self-check verifies RGB trio counts, radius ranges, non-overlapping target placement, screen bounds, and hit/miss calculation.
