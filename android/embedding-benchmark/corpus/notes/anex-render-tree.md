# Accessible equation layout in the annex reader

The annex reader shows calculus lecture notes on tablets at the tutoring centre. A note can contain a proof, a histogram, and a table of coefficient values. Students can select prose, copy a displayed expression, and walk links with a screen reader. One lesson showed its final formula on the preview card but hid the line below it in the reading pane. Its source included two empty lines between aligned cases. A student who copied the derivation got an extra line and asked whether the extra line counted as part of the displayed formula. We used a local fixture with no network images to compare the two rendering paths.

## Layout choices

The rich preview has only a few visible lines, so it draws block equations without a horizontal scroll affordance. The reading pane allows scrolling inside a wide equation without moving the whole page sideways. When a coefficient takes several tokens, wrap can split a prose sentence naturally while keeping the equation at the next allowed break. The source ranges provide click targets for anchors and allow the reader to move between source and preview.

| Case | Expected rendering |
| --- | --- |
| Formula followed by a list | Close the formula before opening the list container |
| Aligned cases inside a display block | Keep the cases in one block |
| Currency in a paragraph | Preserve literal dollar sign |
| Code displaying a dollar expression | Keep the code face and ignore equation delimiters |

### Reproduction

```markdown
A workshop ticket costs $15 before noon.

$$
x = a + b

y = a - b
$$

- [ ] Check the denominator.
```

The third and fourth lines belong to a single display block. The visual renderer had originally borrowed a paragraph preprocessor from the preview. That preprocessor normalizes empty lines and returns plain text. It was useful for clipping cards, but changes the location of the checkbox compared with the source.

> "The check mark landed in the right place on screen. The saved change landed in a different place in the document."

## Selection and storage

The reader tracks absolute offsets held by the parser. A user can select a frontmatter value in source view and a list item in rendered view. Both selections refer to the same bytes after UTF-8 decoding, expressed in the platform's string offsets. Surrogate pairs need correct treatment because the parser reports code-unit positions. The source remains the input for content hashing, so the visual layer's substitutions never appear as an edit.

### Empty lines inside a formula

Some engines interpret a blank line inside display math as the end of its surrounding paragraph. We can preserve the visible empty row by replacing one line character with an invisible marker at render time. This deliberately leaves the source indices on either side of the formula fixed. The math preprocessor must preserve string length because every interactive checkbox uses an offset into the original Markdown.
