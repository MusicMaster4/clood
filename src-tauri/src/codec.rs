use serde::{Deserialize, Serialize};

/// Packet-independent low-latency audio wire codecs for 24 kHz mono voice over Tailscale.
///
/// Frame wire layout:
/// - byte 0: codec id (0 = pcm16, 1 = adpcm_24k ~96 kbps, 2 = ulaw_24k 192 kbps)
/// - byte 1: quantized speech RMS level (0..=255)
/// - bytes 2..: codec payload
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AudioCodec {
    Adpcm24k,
    Ulaw24k,
}

impl Default for AudioCodec {
    fn default() -> Self {
        Self::Adpcm24k
    }
}

impl AudioCodec {
    pub fn wire_id(self) -> u8 {
        match self {
            Self::Adpcm24k => 1,
            Self::Ulaw24k => 2,
        }
    }
}

const IMA_INDEX_TABLE: [i32; 16] = [
    -1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8,
];

const IMA_STEP_TABLE: [i32; 89] = [
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66,
    73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449,
    494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272,
    2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493,
    10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
];

/// Computes normalized perceptual speech loudness (0.0..=1.0) from 16-bit PCM samples.
pub fn pcm_rms_level(samples: &[i16]) -> f32 {
    if samples.is_empty() {
        return 0.0;
    }
    let sum_sq: f64 = samples
        .iter()
        .map(|&s| {
            let v = f64::from(s) / 32768.0;
            v * v
        })
        .sum();
    let rms = (sum_sq / samples.len() as f64).sqrt() as f32;
    // Map typical speech RMS (~0.01..0.22) smoothly to 0..1
    let scaled = ((rms - 0.008) / 0.18).clamp(0.0, 1.0);
    scaled.powf(0.65)
}

pub fn encode_wire_frame(samples: &[i16], codec: AudioCodec) -> (Vec<u8>, f32) {
    let level = pcm_rms_level(samples);
    let level_u8 = (level * 255.0).round().clamp(0.0, 255.0) as u8;

    let mut out = Vec::with_capacity(samples.len() + 8);
    out.push(codec.wire_id());
    out.push(level_u8);

    match codec {
        AudioCodec::Adpcm24k => encode_ima_adpcm_into(samples, &mut out),
        AudioCodec::Ulaw24k => {
            for &s in samples {
                out.push(linear_to_ulaw(s));
            }
        }
    }

    (out, level)
}

pub fn decode_wire_frame(frame: &[u8]) -> Option<(Vec<i16>, f32)> {
    if frame.len() < 2 {
        return None;
    }
    let codec_id = frame[0];
    let level = f32::from(frame[1]) / 255.0;
    let payload = &frame[2..];

    let samples = match codec_id {
        0 => {
            let count = payload.len() / 2;
            let mut out = Vec::with_capacity(count);
            for chunk in payload.chunks_exact(2) {
                out.push(i16::from_le_bytes([chunk[0], chunk[1]]));
            }
            out
        }
        1 => decode_ima_adpcm(payload)?,
        2 => payload.iter().map(|&b| ulaw_to_linear(b)).collect(),
        _ => return None,
    };

    Some((samples, level))
}

pub fn pcm_le_bytes_to_samples(bytes: &[u8]) -> Vec<i16> {
    bytes
        .chunks_exact(2)
        .map(|c| i16::from_le_bytes([c[0], c[1]]))
        .collect()
}

pub fn samples_to_pcm_le_bytes(samples: &[i16]) -> Vec<u8> {
    let mut out = Vec::with_capacity(samples.len() * 2);
    for &s in samples {
        out.extend_from_slice(&s.to_le_bytes());
    }
    out
}

fn encode_ima_adpcm_into(samples: &[i16], out: &mut Vec<u8>) {
    if samples.is_empty() {
        return;
    }
    let mut predictor = i32::from(samples[0]);
    // Estimate initial step index from first delta to keep transient clicks minimal.
    let mut index: i32 = if samples.len() > 1 {
        let diff = (i32::from(samples[1]) - predictor).abs();
        IMA_STEP_TABLE
            .iter()
            .position(|&s| s >= diff)
            .unwrap_or(88) as i32
    } else {
        0
    };

    let count = samples.len() as u16;
    out.extend_from_slice(&samples[0].to_le_bytes());
    out.push(index as u8);
    out.extend_from_slice(&count.to_le_bytes());

    let mut pending_low: Option<u8> = None;
    for &sample in &samples[1..] {
        let step = IMA_STEP_TABLE[index as usize];
        let mut diff = i32::from(sample) - predictor;
        let mut nibble: u8 = 0;
        if diff < 0 {
            nibble = 8;
            diff = -diff;
        }
        let mut delta = step >> 3;
        if diff >= step {
            nibble |= 4;
            diff -= step;
            delta += step;
        }
        if diff >= (step >> 1) {
            nibble |= 2;
            diff -= step >> 1;
            delta += step >> 1;
        }
        if diff >= (step >> 2) {
            nibble |= 1;
            delta += step >> 2;
        }
        if (nibble & 8) != 0 {
            predictor -= delta;
        } else {
            predictor += delta;
        }
        predictor = predictor.clamp(-32768, 32767);
        index = (index + IMA_INDEX_TABLE[nibble as usize]).clamp(0, 88);

        if let Some(low) = pending_low.take() {
            out.push((low & 0x0f) | ((nibble & 0x0f) << 4));
        } else {
            pending_low = Some(nibble & 0x0f);
        }
    }
    if let Some(low) = pending_low {
        out.push(low & 0x0f);
    }
}

fn decode_ima_adpcm(payload: &[u8]) -> Option<Vec<i16>> {
    if payload.len() < 5 {
        return None;
    }
    let mut predictor = i32::from(i16::from_le_bytes([payload[0], payload[1]]));
    let mut index = i32::from(payload[2]).clamp(0, 88);
    let total_samples = usize::from(u16::from_le_bytes([payload[3], payload[4]]));
    if total_samples == 0 {
        return Some(Vec::new());
    }

    let mut out = Vec::with_capacity(total_samples);
    out.push(predictor as i16);

    for &byte in &payload[5..] {
        for shift in [0u8, 4u8] {
            if out.len() >= total_samples {
                break;
            }
            let nibble = (byte >> shift) & 0x0f;
            let step = IMA_STEP_TABLE[index as usize];
            let mut delta = step >> 3;
            if (nibble & 4) != 0 {
                delta += step;
            }
            if (nibble & 2) != 0 {
                delta += step >> 1;
            }
            if (nibble & 1) != 0 {
                delta += step >> 2;
            }
            if (nibble & 8) != 0 {
                predictor -= delta;
            } else {
                predictor += delta;
            }
            predictor = predictor.clamp(-32768, 32767);
            index = (index + IMA_INDEX_TABLE[nibble as usize]).clamp(0, 88);
            out.push(predictor as i16);
        }
    }

    Some(out)
}

fn linear_to_ulaw(sample: i16) -> u8 {
    const BIAS: i32 = 0x84;
    const MAX: i32 = 32635;
    let mut pcm = i32::from(sample);
    let sign = if pcm < 0 {
        pcm = -pcm;
        0x80u8
    } else {
        0u8
    };
    if pcm > MAX {
        pcm = MAX;
    }
    pcm += BIAS;

    let mut exponent = 7u8;
    let mut mask = 0x4000;
    while exponent > 0 && (pcm & mask) == 0 {
        exponent -= 1;
        mask >>= 1;
    }
    let mantissa = ((pcm >> (exponent + 3)) & 0x0f) as u8;
    !(sign | (exponent << 4) | mantissa)
}

fn ulaw_to_linear(ulaw_byte: u8) -> i16 {
    let u = !ulaw_byte;
    let sign = u & 0x80;
    let exponent = (u >> 4) & 0x07;
    let mantissa = i32::from(u & 0x0f);
    let mut t = ((mantissa << 3) + 0x84) << exponent;
    t -= 0x84;
    if sign != 0 {
        (-t) as i16
    } else {
        t as i16
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sine_wave(len: usize) -> Vec<i16> {
        (0..len)
            .map(|i| {
                let t = i as f32 * 440.0 * std::f32::consts::TAU / 24000.0;
                (t.sin() * 14000.0) as i16
            })
            .collect()
    }

    #[test]
    fn adpcm_roundtrip_preserves_shape_and_size() {
        let input = sine_wave(480);
        let (frame, level) = encode_wire_frame(&input, AudioCodec::Adpcm24k);
        assert!(level > 0.4);
        assert!(frame.len() <= 250);
        let (decoded, decoded_level) = decode_wire_frame(&frame).unwrap();
        assert_eq!(decoded.len(), input.len());
        assert!((decoded_level - level).abs() < 0.02);
        let max_err = input
            .iter()
            .zip(decoded.iter())
            .map(|(&a, &b)| (i32::from(a) - i32::from(b)).abs())
            .max()
            .unwrap();
        assert!(max_err < 1600, "unexpected ADPCM error: {max_err}");
    }

    #[test]
    fn ulaw_roundtrip_preserves_shape() {
        let input = sine_wave(480);
        let (frame, _) = encode_wire_frame(&input, AudioCodec::Ulaw24k);
        assert_eq!(frame.len(), 482);
        let (decoded, _) = decode_wire_frame(&frame).unwrap();
        assert_eq!(decoded.len(), input.len());
        let max_err = input
            .iter()
            .zip(decoded.iter())
            .map(|(&a, &b)| (i32::from(a) - i32::from(b)).abs())
            .max()
            .unwrap();
        assert!(max_err < 800, "unexpected u-law error: {max_err}");
    }
}
