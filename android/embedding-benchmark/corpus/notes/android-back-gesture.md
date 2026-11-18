# Reading pane back gesture experiment

The library tablet opens a reading pane above a two-column browse screen. Test readers navigate with both the system three-button bar and the edge swipe. The pane shows a chapter with highlights, remote footnotes, and a local bookmark. During the pilot we animated the pane immediately upon receiving any edge movement. One tester scrubbing a slider on a footnote moved far enough to start the swipe and then returned to the same chapter. The thumbnail receded, expanded again, and the read position jumped two sections. Returning to the browse screen by pressing the toolbar arrow worked every time.

## What to observe

The reading pane and browse card are backed by different screen state objects. During motion, both remain in composition so the card has a stable corner and title to animate towards. The browse screen is visually present but has no focus until it becomes the active destination.

| Gesture | Starting view | Expected state after input |
| --- | --- | --- |
| Back button | Chapter pane | Browse list, anchored to the old card |
| Half swipe then retreat | Chapter pane | Same chapter, same scroll offset |
| Swipe through threshold | Chapter pane | Browse list with selected card visible |
| Drag slider from edge | Chapter pane | Slider changes and chapter stays open |

### Test reader account

The account used for the pilot contained one offline chapter and two articles with slow remote illustrations. We tried an unpopulated browse list to ensure the transition did not rely on a thumbnail. When an illustration loads mid-gesture the preview can resize, so the animation tracks the pane bounds rather than the bitmap size.

> "I changed my mind halfway through. I expected the chapter to be where I left it."

## Instrumentation

Record the destination depth, current chapter key, scroll position, input progress, and completion signal in a single frame trace. A trace that reports only destination depth misses the visible jump if the pane goes away and gets recreated before sampling. Capture the old and new reading positions around the terminal event.

```text
progress(0.15)
progress(0.54)
progress(0.17)
cancelled
```

Run the same sequence with touch exploration enabled. The screen reader should announce the active pane and recover control after an aborted drag. The next attempt must still animate smoothly. Record a screen capture with the navigation handle visible so reviewers can time the event against the edge motion.

### Destination lifetime

A previous prototype popped the pane at the beginning of the transition and resurrected it during cancellation. That created a fresh chapter state and lost the bookmark draft. The host now keeps its original entry throughout the drag and resets the animation when the drag is abandoned. Only the committed gesture removes the destination from the navigation stack.
