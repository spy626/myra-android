package com.myra.assistant.ui.workspace

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Native block renderer for Chat assistant prose. No WebView, remote images or simulated
 * branded app icons. Casual replies stay a simple paragraph. Original message remains stored,
 * copied, retried and sent to the provider unchanged.
 */
internal object WorkspaceRichAnswerView {
    private val bodyColor = Color.rgb(230, 236, 244)
    private val mutedColor = Color.rgb(172, 190, 180)
    private val accentColor = Color.rgb(156, 232, 188)
    private val borderColor = Color.rgb(43, 59, 49)

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
            setLinkTextColor(WorkspaceChatReadability.verifiedLinkColor)
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
                            setTextColor(accentColor)
                        }, LinearLayout.LayoutParams(dp(context, 18), -2))
                        line.addView(label(context, item.text, 16.5f),
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
                        val number = label(context, item.number, 13.5f, bold = true).apply {
                            setTextColor(accentColor)
                            gravity = android.view.Gravity.CENTER
                            background = GradientDrawable().apply {
                                setColor(Color.rgb(22, 49, 35))
                                cornerRadius = dp(context, 10).toFloat()
                            }
                        }
                        line.addView(number, LinearLayout.LayoutParams(
                            dp(context, 29), dp(context, 29)
                        ).apply { rightMargin = dp(context, 11) })
                        line.addView(label(context, item.text), LinearLayout.LayoutParams(0, -2, 1f))
                        put(group, line, context,
                            top = if (stepIndex == 0) 3 else 12, bottom = 5)
                    }
                    put(root, group, context, top = 2, bottom = 7)
                }
                is WorkspaceRichAnswerBlocks.Block.Table -> {
                    val group = column(context)
                    block.rows.forEachIndexed { rowIndex, cells ->
                        val item = column(context).apply {
                            setPadding(dp(context, 12), dp(context, 10),
                                dp(context, 12), dp(context, 10))
                            background = GradientDrawable().apply {
                                setColor(Color.rgb(19, 28, 23))
                                cornerRadius = dp(context, 11).toFloat()
                                setStroke(dp(context, 1), borderColor)
                            }
                        }
                        put(item, label(context, cells[0], 16.5f, bold = true),
                            context, bottom = if (cells.size > 1) 4 else 0)
                        cells.drop(1).forEachIndexed { valueIndex, value ->
                            put(item, label(context, "**" + block.headers[valueIndex + 1] +
                                ":** " + value, 15.5f), context, top = 1)
                        }
                        put(group, item, context, top = if (rowIndex == 0) 2 else 6)
                    }
                    put(root, group, context, top = 4, bottom = 7)
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
}
