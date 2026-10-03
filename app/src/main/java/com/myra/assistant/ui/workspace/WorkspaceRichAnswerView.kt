package com.myra.assistant.ui.workspace

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.RadioGroup
import android.widget.RadioButton
import com.google.android.material.button.MaterialButton
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView

/**
 * Native block renderer for Chat assistant prose. No WebView, remote images or simulated
 * branded app icons. Casual replies stay a simple paragraph. Original message remains stored,
 * copied, retried and sent to the provider unchanged.
 */
internal object WorkspaceRichAnswerView {
    // Answer-only palette: neutral ChatGPT-like surfaces. The parent chat
    // send button, user bubble, and input bar retain their independent LYRA theme.
    private val bodyColor = Color.rgb(232, 232, 232)
    private val mutedColor = Color.rgb(174, 174, 174)
    private val borderColor = Color.argb(20, 255, 255, 255) // ~8% white

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun column(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun row(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private fun label(
        context: Context,
        markdown: String,
        fontSp: Float = WorkspaceChatReadability.assistant.fontSp,
        bold: Boolean = false,
        muted: Boolean = false,
    ): TextView = TextView(context).apply {
        textSize = fontSp
        setTextColor(if (muted) mutedColor else bodyColor)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(context, 2).toFloat(), 1.07f)
        includeFontPadding = true
        val formatted = WorkspaceMarkdownText.render(markdown)
        text = formatted
        if ((formatted as? Spanned)?.getSpans(
                0, formatted.length, URLSpan::class.java
            )?.isNotEmpty() == true
        ) {
            setLinkTextColor(mutedColor)
            movementMethod = LinkMovementMethod.getInstance()
        } else {
            setTextIsSelectable(true)
        }
    }

    private fun put(
        parent: LinearLayout, view: View, context: Context,
        top: Int = 0, bottom: Int = 0,
    ) {
        parent.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(context, top)
            bottomMargin = dp(context, bottom)
        })
    }

    /** Shared full-width neutral table for JSON and Markdown answer blocks. */
    private fun neutralTable(
        context: Context, headers: List<String>, rows: List<List<String>>,
    ): View {
        val horizontal = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
        }
        val table = TableLayout(context).apply {
            isStretchAllColumns = true
            isShrinkAllColumns = false
        }
        (listOf(headers) + rows).forEachIndexed { rowIndex, cells ->
            val line = TableRow(context).apply { gravity = Gravity.CENTER_VERTICAL }
            cells.forEach { cell ->
                val view = label(context, WorkspaceRichStatusText.neutralize(cell),
                    if (rowIndex == 0) 13.5f else 14.5f, bold = rowIndex == 0).apply {
                    setPadding(dp(context, 7),
                        dp(context, if (rowIndex == 0) 11 else 13),
                        dp(context, 7),
                        dp(context, if (rowIndex == 0) 11 else 13))
                    minWidth = dp(context, if (headers.size == 2) 122 else 82)
                    // No distinct header fill; only typography and hairline dividers.
                }
                line.addView(view, TableRow.LayoutParams(0, -2, 1f))
            }
            table.addView(line, TableLayout.LayoutParams(-1, -2))
            if (rowIndex < rows.size) {
                table.addView(View(context).apply { setBackgroundColor(borderColor) },
                    TableLayout.LayoutParams(-1, dp(context, 1)))
            }
        }
        horizontal.addView(table, android.widget.FrameLayout.LayoutParams(-1, -2))
        return horizontal
    }

    fun create(context: Context, raw: String): View {
        val blocks = WorkspaceRichAnswerBlocks.parse(raw)
        val root = column(context).apply {
            setPadding(dp(context, 9), dp(context, 6), dp(context, 9), dp(context, 10))
            // Body text spans are individually selectable or have safe, verified links.
        }
        blocks.forEachIndexed { index, block ->
            when (block) {
                is WorkspaceRichAnswerBlocks.Block.Paragraph -> {
                    put(root, label(context, block.text), context,
                        top = if (index == 0) 2 else 5, bottom = 5)
                }
                is WorkspaceRichAnswerBlocks.Block.Heading -> {
                    val size = when (block.level) {
                        1 -> 20f
                        2 -> 18.5f
                        else -> 17.5f
                    }
                    put(root, label(context, block.text, size, bold = true), context,
                        top = if (index == 0) 3 else 13, bottom = 4)
                }
                is WorkspaceRichAnswerBlocks.Block.Bullets -> {
                    val group = column(context)
                    block.items.forEach { item ->
                        val line = row(context).apply {
                            setPadding(dp(context, 7 + item.depth * 13), 0, 0, 0)
                        }
                        line.addView(label(context, "•", 17f).apply {
                            setTextColor(mutedColor)
                        }, LinearLayout.LayoutParams(dp(context, 18), -2))
                        line.addView(label(context, WorkspaceRichStatusText.neutralize(item.text), 16.5f),
                            LinearLayout.LayoutParams(0, -2, 1f))
                        put(group, line, context, top = 3, bottom = 3)
                    }
                    put(root, group, context, top = 2, bottom = 7)
                }
                is WorkspaceRichAnswerBlocks.Block.Numbered -> {
                    val group = column(context)
                    block.items.forEachIndexed { stepIndex, item ->
                        val line = row(context).apply {
                            setPadding(dp(context, 1), 0, 0, 0)
                        }
                        val number = label(context, item.number + ".", 16f, bold = true).apply {
                            setTextColor(bodyColor)
                            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        }
                        line.addView(number, LinearLayout.LayoutParams(
                            dp(context, 25), -2
                        ).apply { rightMargin = dp(context, 6) })
                        val content = column(context)
                        put(content, label(context, WorkspaceRichStatusText.neutralize(item.text)), context)
                        item.details.forEach { detail ->
                            val nested = row(context).apply {
                                setPadding(dp(context, 3), 0, 0, 0)
                            }
                            nested.addView(label(context, "•", 15f).apply {
                                setTextColor(mutedColor)
                            }, LinearLayout.LayoutParams(dp(context, 17), -2))
                            nested.addView(label(context, WorkspaceRichStatusText.neutralize(detail), 15.5f),
                                LinearLayout.LayoutParams(0, -2, 1f))
                            put(content, nested, context, top = 4)
                        }
                        line.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
                        put(group, line, context,
                            top = if (stepIndex == 0) 3 else 12, bottom = 5)
                    }
                    put(root, group, context, top = 2, bottom = 7)
                }
                is WorkspaceRichAnswerBlocks.Block.Table -> {
                    put(root, neutralTable(context, block.headers, block.rows), context,
                        top = 5, bottom = 9)
                }
                is WorkspaceRichAnswerBlocks.Block.Quote -> {
                    val quote = row(context)
                    quote.addView(View(context).apply {
                        setBackgroundColor(borderColor)
                    }, LinearLayout.LayoutParams(dp(context, 3), -1).apply {
                        rightMargin = dp(context, 11)
                    })
                    quote.addView(label(context, block.text, 16f, muted = true),
                        LinearLayout.LayoutParams(0, -2, 1f))
                    put(root, quote, context, top = 6, bottom = 8)
                }
                WorkspaceRichAnswerBlocks.Block.Divider -> {
                    put(root, View(context).apply {
                        setBackgroundColor(borderColor)
                        minimumHeight = dp(context, 1)
                    }, context, top = 9, bottom = 8)
                }
            }
        }
        return root
    }

    /** JSON-driven Android Views renderer; old Markdown path remains for historical replies. */
    fun createBlocks(
        context: Context,
        blocks: List<Block>,
        onOptionSelected: (String) -> Unit,
    ): View {
        val root = column(context).apply {
            // Transparent reading surface; never create a grey outer response card.
            setPadding(dp(context, 5), dp(context, 5), dp(context, 5), dp(context, 8))
        }
        blocks.forEach { block ->
            when (block) {
                is Block.Text -> put(root, label(context, block.text, 16f), context,
                    top = 3, bottom = 6)
                is Block.Heading -> put(root, label(context,
                    (if (block.emoji.isBlank()) "" else block.emoji + " ") + block.text,
                    fontSp = 17.5f, bold = true), context, top = 12, bottom = 5)
                is Block.Bullets -> {
                    val items = column(context)
                    block.items.forEach { item ->
                        val line = row(context)
                        line.addView(label(context, "•", 17f).apply {
                            setTextColor(mutedColor)
                        }, LinearLayout.LayoutParams(dp(context, 20), -2))
                        line.addView(label(context, WorkspaceRichStatusText.neutralize(item), 15.5f),
                            LinearLayout.LayoutParams(0, -2, 1f))
                        put(items, line, context, top = 3, bottom = 3)
                    }
                    put(root, items, context, top = 2, bottom = 7)
                }
                is Block.Table -> {
                    put(root, neutralTable(context, block.columns, block.rows),
                        context, top = 5, bottom = 9)
                }
                is Block.ImageRow -> {
                    // Search placeholder is not an image: no framed blank area.
                    put(root, label(context,
                        block.caption.ifBlank { block.query }, 14f, muted = true),
                        context, top = 3, bottom = 6)
                }
                is Block.AppCards -> {
                    // Inline compact app icon rows, not grey boxes or an app dashboard.
                    block.items.forEach { (name, note) ->
                        val line = row(context).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(context, 1), dp(context, 5),
                                dp(context, 1), dp(context, 5))
                        }
                        val badge: View = WorkspaceLocalAppIcons.icon(name)?.let { drawable ->
                            ImageView(context).apply {
                                setImageDrawable(drawable)
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                contentDescription = "$name local icon"
                                setPadding(dp(context, 2), dp(context, 2),
                                    dp(context, 2), dp(context, 2))
                            }
                        } ?: TextView(context).apply {
                            text = WorkspaceLocalAppIcons.glyph(name)
                            gravity = Gravity.CENTER
                            textSize = 16f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(mutedColor)
                            contentDescription = "$name initial"
                        }
                        line.addView(badge, LinearLayout.LayoutParams(
                            dp(context, 34), dp(context, 34)
                        ).apply { rightMargin = dp(context, 10) })
                        val details = column(context)
                        put(details, label(context, name, 15.5f, bold = true), context)
                        if (note.isNotBlank())
                            put(details, label(context, note, 14f, muted = true), context, top = 2)
                        line.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                        put(root, line, context, bottom = 3)
                    }
                }
                is Block.Callout -> {
                    // Tip is normal reading content, never a highlighted card.
                    if (block.label.isNotBlank()) {
                        put(root, label(context, block.label, 14f, bold = true),
                            context, top = 5, bottom = 2)
                    }
                    put(root, label(context, block.text, 15.5f),
                        context, bottom = 7)
                }
                is Block.MockupCard -> {
                    // Legacy mockups become inline notes: bounded WRAP_CONTENT, no
                    // weighted-height children, inner panels or giant grey rectangles.
                    put(root, label(context, block.title, 15f, bold = true),
                        context, top = 5, bottom = 3)
                    val content = block.items.take(4).joinToString("  •  ") {
                        WorkspaceRichStatusText.neutralize(it)
                    }
                    if (content.isNotBlank()) {
                        put(root, label(context, content, 14.5f, muted = true),
                            context, bottom = 6)
                    }
                }
                Block.Divider -> put(root, View(context).apply {
                    setBackgroundColor(borderColor)
                    minimumHeight = dp(context, 1)
                }, context, top = 8, bottom = 8)
                is Block.Options -> {
                    val options = column(context).apply {
                        setPadding(dp(context, 2), dp(context, 4),
                            dp(context, 2), dp(context, 4))
                    }
                    put(options, label(context, block.question, 16f, bold = true),
                        context, bottom = 5)
                    val choices = RadioGroup(context).apply {
                        orientation = RadioGroup.VERTICAL
                    }
                    block.choices.forEach { choice ->
                        choices.addView(RadioButton(context).apply {
                            id = View.generateViewId()
                            text = choice
                            textSize = 14f
                            setTextColor(bodyColor)
                            buttonTintList = ColorStateList.valueOf(mutedColor)
                            tag = choice
                        }, RadioGroup.LayoutParams(-1, -2))
                    }
                    put(options, choices, context, bottom = 7)
                    val button = MaterialButton(context).apply {
                        text = "Continue  →"
                        isEnabled = false
                        setTextColor(bodyColor)
                        backgroundTintList = ColorStateList.valueOf(Color.rgb(62, 62, 62))
                        setOnClickListener {
                            val picked = choices.findViewById<RadioButton>(choices.checkedRadioButtonId)
                            val selected = picked?.tag as? String
                            if (!selected.isNullOrBlank()) {
                                isEnabled = false
                                onOptionSelected(selected)
                            }
                        }
                    }
                    choices.setOnCheckedChangeListener { _, checked ->
                        button.isEnabled = checked != -1
                    }
                    put(options, button, context)
                    put(root, options, context, top = 7, bottom = 8)
                }
            }
        }
        return root
    }
}

