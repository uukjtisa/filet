---
title: Everything
tags: [reader, fixture]
---

# Everything

A README that uses every part of GitHub's Markdown the reader draws, so one file shows them all.
Jump to [the tables](#tables) or [the code](#code), or read about it on [GitHub](https://github.com).

<p align="center">
  <img src="docs/logo.png" alt="Logo" width="96">
  <br>
  <b>Centred, the way a README header is</b>
</p>

> [!NOTE]
> Alerts are quotes with a type, and the type gives them a colour and an icon.

> [!WARNING]
> This one is a warning, with **bold** and `code` inside.

> A plain quote, for comparison.

## Lists

- A bullet
    - A nested bullet
        - And one deeper
- Back out

1. First
2. Second
3. Third

A list numbers on from where it starts, whatever the later numbers say, so this one starts at eight:

8. Eighth
9. Ninth
10. Tenth, so the numbers line up on the right

- [x] A task that is done
- [ ] A task that is not

## Tables

| Left | Centre | Right |
| :--- | :---: | ---: |
| a | b | 1 |
| a much longer cell that should wrap when the screen is narrow | centred | 1,024 |

## Code

```kotlin
fun main() {
    val greeting = "hello"
    println(greeting)
}
```

Inline `code`, ~~struck~~, *emphasis*, and an autolink: https://example.com/page.

## Pictures

![A picture beside the README](docs/shot.png)

![Build](https://img.shields.io/badge/build-passing-green.svg)

## Folds

<details>
<summary>Click to open</summary>

Hidden until the summary is tapped.

</details>

## Notes

A sentence with a footnote.[^1]

---

[^1]: The footnote, gathered at the foot with a link back.
