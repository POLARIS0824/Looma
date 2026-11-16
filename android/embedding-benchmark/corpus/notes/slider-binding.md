# Edge swipe sensitivity log

This prototype adjusts the side slider's progress when the finger enters the bezel. Its state machine has cancel and commit events for brightness control. App navigation remains outside the controls.

A cancelled brightness drag restores the last accepted screen level and leaves the slider thumb at the initial percentage.

Do not set the final brightness until onStopTrackingTouch has reported a release.
