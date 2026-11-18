//! GF(2^8) Random Linear Network Coding (RLNC, W = 16) for lossy cellular recovery.
//! Uses dual 16-entry nibble lookup tables compatible with ARM64 NEON `vqtbl1q_u8`.

pub const RLNC_WINDOW: usize = 16;
const GF256_POLY: u16 = 0x11D; // x^8 + x^4 + x^3 + x^2 + 1

pub struct Gf256Tables {
    pub lo_lut: [u8; 16],
    pub hi_lut: [u8; 16],
}

#[inline]
pub fn gf256_mul(mut a: u8, mut b: u8) -> u8 {
    let mut acc: u8 = 0;
    for _ in 0..8 {
        if (b & 1) != 0 {
            acc ^= a;
        }
        let hi_bit = (a & 0x80) != 0;
        a <<= 1;
        if hi_bit {
            a ^= (GF256_POLY & 0xFF) as u8;
        }
        b >>= 1;
    }
    acc
}

pub fn build_dual_lut(coeff: u8) -> Gf256Tables {
    let mut lo_lut = [0u8; 16];
    let mut hi_lut = [0u8; 16];
    for i in 0..16u8 {
        lo_lut[i as usize] = gf256_mul(coeff, i);
        hi_lut[i as usize] = gf256_mul(coeff, i << 4);
    }
    Gf256Tables { lo_lut, hi_lut }
}

/// Encodes a repair symbol by XOR-accumulating `src` scaled by `coeff` over GF(2^8).
pub fn gf256_mul_acc(dst: &mut [u8], src: &[u8], coeff: u8) {
    let tables = build_dual_lut(coeff);
    for (d, &s) in dst.iter_mut().zip(src.iter()) {
        let lo = (s & 0x0F) as usize;
        let hi = (s >> 4) as usize;
        *d ^= tables.lo_lut[lo] ^ tables.hi_lut[hi];
    }
}
