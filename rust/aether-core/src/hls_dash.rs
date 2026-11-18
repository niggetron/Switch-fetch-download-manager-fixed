//! Adaptive HLS (.m3u8) and DASH (.mpd) manifest parser & segment list builder.
//! Feeds fragmented .ts / .m4s segments into the same BoundedSlabPool + Earliest-Arrival
//! Wi-Fi/5G scheduler and writes sequentially for clean .mp4 / .ts playback.

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdaptiveMediaSegment {
    pub index: usize,
    pub url: String,
    pub byte_range: Option<(u64, u64)>,
    pub is_init: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ManifestParseResult {
    MasterVariant {
        best_variant_url: String,
        bandwidth: u64,
    },
    MediaSegments(Vec<AdaptiveMediaSegment>),
}

pub fn is_adaptive_manifest_url(url: &str) -> bool {
    let lower = url.split('?').next().unwrap_or(url).to_ascii_lowercase();
    lower.ends_with(".m3u8") || lower.ends_with(".mpd")
}

/// Parses an HLS `.m3u8` master or media playlist resolving relative segment URIs cleanly.
pub fn parse_hls_m3u8(base_url: &str, body: &str) -> Option<ManifestParseResult> {
    let base = url::Url::parse(base_url).ok()?;
    let lines: Vec<&str> = body.lines().map(str::trim).collect();

    // Check Master playlist (#EXT-X-STREAM-INF) -> pick highest bandwidth variant
    if lines.iter().any(|l| l.starts_with("#EXT-X-STREAM-INF")) {
        let mut best_bw = 0u64;
        let mut best_url: Option<String> = None;
        for i in 0..lines.len() {
            if let Some(attrs) = lines[i].strip_prefix("#EXT-X-STREAM-INF:") {
                let bw = attrs
                    .split(',')
                    .find_map(|p| p.trim().strip_prefix("BANDWIDTH="))
                    .and_then(|v| v.parse::<u64>().ok())
                    .unwrap_or(1_000_000);
                if let Some(next_line) = lines.get(i + 1) {
                    if !next_line.is_empty() && !next_line.starts_with('#') && bw >= best_bw {
                        if let Ok(resolved) = base.join(next_line) {
                            best_bw = bw;
                            best_url = Some(resolved.to_string());
                        }
                    }
                }
            }
        }
        return best_url.map(|u| ManifestParseResult::MasterVariant {
            best_variant_url: u,
            bandwidth: best_bw,
        });
    }

    // Media playlist -> extract optional init segment + ordered TS/M4S segments
    let mut segments = Vec::new();
    let mut expect_seg = false;
    for line in lines {
        if let Some(map_attrs) = line.strip_prefix("#EXT-X-MAP:") {
            if let Some(uri_start) = map_attrs.find("URI=\"") {
                let rest = &map_attrs[uri_start + 5..];
                if let Some(uri_end) = rest.find('"') {
                    if let Ok(resolved) = base.join(&rest[..uri_end]) {
                        segments.push(AdaptiveMediaSegment {
                            index: segments.len(),
                            url: resolved.to_string(),
                            byte_range: None,
                            is_init: true,
                        });
                    }
                }
            }
        } else if line.starts_with("#EXTINF:") {
            expect_seg = true;
        } else if expect_seg && !line.is_empty() && !line.starts_with('#') {
            if let Ok(resolved) = base.join(line) {
                segments.push(AdaptiveMediaSegment {
                    index: segments.len(),
                    url: resolved.to_string(),
                    byte_range: None,
                    is_init: false,
                });
            }
            expect_seg = false;
        }
    }

    if segments.is_empty() {
        None
    } else {
        Some(ManifestParseResult::MediaSegments(segments))
    }
}

/// Lightweight DASH `.mpd` `<BaseURL>` & `<SegmentURL media="...">` extractor.
pub fn parse_dash_mpd(base_url: &str, xml: &str) -> Option<Vec<AdaptiveMediaSegment>> {
    let base = url::Url::parse(base_url).ok()?;
    let mut segments = Vec::new();
    let mut search_from = 0usize;

    while let Some(pos) = xml[search_from..].find("media=\"") {
        let start = search_from + pos + 7;
        if let Some(end) = xml[start..].find('"') {
            let candidate = &xml[start..start + end];
            if !candidate.contains('$') {
                if let Ok(resolved) = base.join(candidate) {
                    segments.push(AdaptiveMediaSegment {
                        index: segments.len(),
                        url: resolved.to_string(),
                        byte_range: None,
                        is_init: false,
                    });
                }
            }
            search_from = start + end + 1;
        } else {
            break;
        }
    }

    if segments.is_empty() {
        // Fallback single BaseURL representation
        if let Some(b_start) = xml.find("<BaseURL>") {
            let s = b_start + 9;
            if let Some(b_end) = xml[s..].find("</BaseURL>") {
                let raw = xml[s..s + b_end].trim();
                if let Ok(resolved) = base.join(raw) {
                    segments.push(AdaptiveMediaSegment {
                        index: 0,
                        url: resolved.to_string(),
                        byte_range: None,
                        is_init: false,
                    });
                }
            }
        }
    }

    if segments.is_empty() {
        None
    } else {
        Some(segments)
    }
}
