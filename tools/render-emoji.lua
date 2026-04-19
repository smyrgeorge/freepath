-- Wrap emoji / pictographic characters in `{\emojifont …}` so an emoji font
-- (defined in the LaTeX template) is used to typeset them instead of the
-- default text font.

local ranges = {
  { 0x2600,  0x27BF  },  -- Miscellaneous Symbols + Dingbats
  { 0x1F000, 0x1FFFF },  -- Supplementary Multilingual Plane pictographs
  { 0xFE0F,  0xFE0F  },  -- Variation selector-16
  { 0x200D,  0x200D  },  -- Zero-width joiner
}

-- Chars the emoji font typically lacks — rendered as LaTeX math symbols instead.
local math_symbols = {
  [0x2610] = "$\\square$",       -- ☐  ballot box
  [0x2611] = "$\\boxdot$",       -- ☑  ballot box with check
  [0x2612] = "$\\boxtimes$",     -- ☒  ballot box with X
}

local function is_emoji(cp)
  for _, r in ipairs(ranges) do
    if cp >= r[1] and cp <= r[2] then return true end
  end
  return false
end

function Str(el)
  local out = {}
  local buf = {}
  local had_emoji = false

  local function flush()
    if #buf > 0 then
      out[#out + 1] = pandoc.Str(table.concat(buf))
      buf = {}
    end
  end

  for _, cp in utf8.codes(el.text) do
    local math = math_symbols[cp]
    if math then
      flush()
      out[#out + 1] = pandoc.RawInline("latex", math)
      had_emoji = true
    elseif is_emoji(cp) then
      flush()
      out[#out + 1] = pandoc.RawInline("latex", "{\\emojifont " .. utf8.char(cp) .. "}")
      had_emoji = true
    else
      buf[#buf + 1] = utf8.char(cp)
    end
  end
  flush()

  if not had_emoji then return nil end
  return out
end
