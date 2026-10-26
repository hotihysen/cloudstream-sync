-- Flimmer-Player für mpv
--
-- Liest die Sitzungsdatei von Flimmer (Folgen und ihre Quellen), spielt ab, wechselt bei Fehlern
-- automatisch die Quelle und geht auf Wunsch (oder am Ende) direkt zur nächsten Folge.
-- Schreibt laufend «Folge Position Dauer» in die Statusdatei, damit Flimmer Weiterschauen,
-- Gist-Sync und Trakt aktualisieren kann.
--
-- Bedienung: N = nächste Folge, S = Quellen, I = Intro überspringen (+85 s)
-- Mit uosc (Ordner portable_config) gibt es dafür auch Knöpfe in der Leiste unten.

local utils = require "mp.utils"
local options = require "mp.options"

local opts = { session = "", status = "" }
options.read_options(opts, "flimmer")

local current = nil        -- Index der laufenden Folge (0-basiert wie in Flimmer)
local link_index = 0       -- Index der laufenden Quelle in der Liste der Folge
local tried = {}           -- bereits versuchte URLs der laufenden Folge
local waiting_next = nil   -- Timer, solange die nächste Folge noch gesucht wird
local countdown = nil      -- Timer für «Nächste Folge in …»
local switching = false    -- gerade beim Wechsel der Quelle/Folge

local function read_session()
    local f = io.open(opts.session, "r")
    if not f then return nil end
    local text = f:read("*a")
    f:close()
    local data = utils.parse_json(text)
    return data
end

local function episode(data, index)
    if not data or not data.episodes then return nil end
    return data.episodes[index + 1]
end

local function has_uosc()
    for _, s in ipairs(mp.get_property_native("script-list") or {}) do
        if s == "uosc" then return true end
    end
    return false
end

local function osd(text, seconds)
    mp.osd_message(text, seconds or 3)
end

local function write_status()
    if current == nil or opts.status == "" then return end
    local pos = mp.get_property_number("time-pos")
    local dur = mp.get_property_number("duration")
    if not pos or not dur then return end
    local f = io.open(opts.status, "w")
    if f then
        f:write(string.format("%d %d %d", current, math.floor(pos * 1000), math.floor(dur * 1000)))
        f:close()
    end
end

-- Kopfzeilen der Quelle setzen und Datei laden
local function load_link(link, start)
    switching = true
    mp.set_property("options/referrer", link.referer or "")
    mp.set_property("options/user-agent", link.ua or
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
    local fields = {}
    if link.headers then
        for k, v in pairs(link.headers) do
            local lk = string.lower(k)
            if lk ~= "referer" and lk ~= "user-agent" then
                table.insert(fields, k .. ": " .. v)
            end
        end
    end
    mp.set_property_native("options/http-header-fields", fields)
    if start and start > 5 then
        mp.set_property("options/start", tostring(math.floor(start)))
    else
        mp.set_property("options/start", "none")
    end
    tried[link.url] = true
    mp.commandv("loadfile", link.url, "replace")
end

-- Nächste noch nicht versuchte Quelle der laufenden Folge
local function next_untried(ep)
    if not ep or not ep.links then return nil end
    for i, l in ipairs(ep.links) do
        if not tried[l.url] then return i, l end
    end
    return nil
end

local function play_episode(index, start)
    local data = read_session()
    local ep = episode(data, index)
    if not ep then return false end
    local i, link = next_untried(ep)
    if not link then return false end
    current = index
    link_index = i
    mp.set_property("force-media-title", ep.title or "Flimmer")
    load_link(link, start or ep.start or 0)
    return true
end

local function cancel_waiting()
    if waiting_next then waiting_next:kill(); waiting_next = nil end
    if countdown then countdown:kill(); countdown = nil end
    mp.remove_key_binding("flimmer-cancel")
end

local function go_next()
    cancel_waiting()
    if current == nil then return end
    local target = current + 1
    local data = read_session()
    local ep = episode(data, target)
    if not ep then
        osd("Das war die letzte Folge.", 4)
        return
    end
    write_status()
    if ep.links and #ep.links > 0 then
        tried = {}
        osd("Nächste Folge: " .. (ep.title or ""), 3)
        play_episode(target, ep.start or 0)
        return
    end
    -- Quellen werden noch gesucht: warten (höchstens 90 Sekunden)
    local waited = 0
    osd("Nächste Folge wird gesucht …", 2)
    waiting_next = mp.add_periodic_timer(1, function()
        waited = waited + 1
        local d = read_session()
        local e = episode(d, target)
        if e and e.links and #e.links > 0 then
            cancel_waiting()
            tried = {}
            osd("Nächste Folge: " .. (e.title or ""), 3)
            play_episode(target, e.start or 0)
        elseif (e and e.searching == false) or waited > 90 then
            cancel_waiting()
            osd("Für die nächste Folge wurde keine Quelle gefunden.", 5)
        else
            mp.osd_message("Nächste Folge wird gesucht … " .. waited .. " s", 1.2)
        end
    end)
end

-- Am Ende: Countdown zur nächsten Folge, Esc bricht ab
local function start_countdown()
    if countdown or current == nil then return end
    local data = read_session()
    if not episode(data, current + 1) then
        osd("Ende", 3)
        mp.add_timeout(4, function() mp.command("quit") end)
        return
    end
    local left = 8
    mp.add_forced_key_binding("ESC", "flimmer-cancel", function()
        cancel_waiting()
        osd("Automatisches Weiterspielen abgebrochen", 3)
    end)
    countdown = mp.add_periodic_timer(1, function()
        left = left - 1
        if left <= 0 then
            go_next()
        else
            mp.osd_message("Nächste Folge in " .. left .. " s   ·   N: jetzt   ·   Esc: abbrechen", 1.2)
        end
    end)
    mp.osd_message("Nächste Folge in " .. left .. " s   ·   N: jetzt   ·   Esc: abbrechen", 1.2)
end

local function show_sources()
    local data = read_session()
    local ep = episode(data, current or 0)
    if not ep or not ep.links or #ep.links == 0 then
        osd("Noch keine Quellen", 2); return
    end
    if has_uosc() then
        local items = {}
        for i, l in ipairs(ep.links) do
            table.insert(items, {
                title = l.name,
                hint = l.quality or "",
                active = (i == link_index),
                value = { "script-message-to", mp.get_script_name(), "switch", l.url },
            })
        end
        local menu = { type = "flimmer_sources", title = "Quellen (" .. #ep.links .. ")", items = items }
        mp.commandv("script-message-to", "uosc", "open-menu", utils.format_json(menu))
    else
        -- Ohne uosc: zur nächsten Quelle wechseln
        local i = (link_index % #ep.links) + 1
        mp.commandv("script-message-to", mp.get_script_name(), "switch", ep.links[i].url)
    end
end

mp.register_script_message("switch", function(url)
    local data = read_session()
    local ep = episode(data, current or 0)
    if not ep or not ep.links then return end
    for i, link in ipairs(ep.links) do
        if link.url == url then
            local pos = mp.get_property_number("time-pos") or 0
            link_index = i
            osd("Quelle: " .. link.name, 3)
            load_link(link, pos)
            return
        end
    end
end)
mp.register_script_message("next", go_next)
mp.register_script_message("sources", show_sources)
mp.register_script_message("skip-intro", function()
    mp.commandv("seek", "85", "relative")
    osd("Intro übersprungen (+85 s)", 2)
end)

mp.add_key_binding("n", "flimmer-next-key", go_next)
mp.add_key_binding("N", "flimmer-next-key2", go_next)
mp.add_key_binding("s", "flimmer-sources-key", show_sources)
mp.add_key_binding("i", "flimmer-intro-key", function() mp.commandv("script-message-to", mp.get_script_name(), "skip-intro") end)

mp.add_periodic_timer(2, write_status)
mp.register_event("seek", write_status)

mp.register_event("file-loaded", function()
    switching = false
    mp.set_property("options/start", "none")
    -- Untertitel der Folge dazunehmen
    local ep = episode(read_session(), current or 0)
    if ep and ep.subs then
        for _, s in ipairs(ep.subs) do
            mp.commandv("sub-add", s.url, "auto", s.lang or "Untertitel")
        end
    end
end)

-- Quelle startet nicht: automatisch die nächste
mp.register_event("end-file", function(e)
    write_status()
    if e.reason == "error" then
        local data = read_session()
        local ep = episode(data, current or 0)
        local i, link = next_untried(ep)
        if link then
            link_index = i
            osd("Quelle startet nicht, versuche: " .. link.name, 3)
            local start = (ep and ep.start) or 0
            load_link(link, start)
        else
            osd("Keine weitere Quelle für diese Folge", 5)
        end
    end
end)

mp.observe_property("eof-reached", "bool", function(_, reached)
    if reached and not switching then start_countdown() end
end)

-- Start: erste Folge der Sitzung
mp.add_timeout(0.2, function()
    local data = read_session()
    if not data then
        osd("Flimmer: Sitzungsdatei fehlt", 5)
        return
    end
    tried = {}
    if not play_episode(data.current or 0, nil) then
        osd("Keine Quelle", 5)
    end
end)
