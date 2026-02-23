-- Lua filter to convert GitHub-style alerts to LaTeX
-- Supports: NOTE, TIP, IMPORTANT, WARNING, CAUTION

function BlockQuote(el)
  -- Check if the first element is a paragraph
  if el.content[1] and el.content[1].t == "Para" then
    local first_inline = el.content[1].content[1]

    -- Check if it starts with [!TYPE]
    if first_inline and first_inline.t == "Str" then
      local alert_type = first_inline.text:match("^%[!(%w+)%]$")

      if alert_type then
        -- Remove the [!TYPE] marker from content
        table.remove(el.content[1].content, 1)

        -- Map alert types to colors and titles
        local alert_configs = {
          NOTE = {color = "blue!10", title = "Note"},
          TIP = {color = "green!10", title = "Tip"},
          IMPORTANT = {color = "purple!10", title = "Important"},
          WARNING = {color = "orange!10", title = "Warning"},
          CAUTION = {color = "red!10", title = "Caution"}
        }

        local config = alert_configs[alert_type] or {color = "gray!10", title = alert_type}

        -- Create LaTeX environment
        local latex_begin = string.format(
          "\\begin{tcolorbox}[colback=%s,colframe=%s,title=\\textbf{%s}]\n",
          config.color, config.color:gsub("!10", "!50"), config.title
        )
        local latex_end = "\n\\end{tcolorbox}"

        -- Return raw LaTeX block with content
        return {
          pandoc.RawBlock("latex", latex_begin),
          pandoc.Div(el.content),
          pandoc.RawBlock("latex", latex_end)
        }
      end
    end
  end

  return el
end

-- Add required LaTeX package to header
function Meta(meta)
  local header_includes = meta['header-includes'] or pandoc.MetaList{}

  -- Add tcolorbox package
  table.insert(header_includes, pandoc.MetaBlocks(
    pandoc.RawBlock("latex", "\\usepackage{tcolorbox}")
  ))

  meta['header-includes'] = header_includes
  return meta
end
