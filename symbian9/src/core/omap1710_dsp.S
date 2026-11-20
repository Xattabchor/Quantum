.syntax unified
.arch armv5te
.arm
.text
.align 2
.global quantum_omap1710_mul_shift12
.type quantum_omap1710_mul_shift12,%function

/*
 * Fast central-range ARMv5TE Q12 product helper. The caller guarantees
 * -2048 <= r1 <= 2047, so fraction*16 is already a signed halfword.
 *
 * r0 = slope, r1 = fraction
 * returns floor((slope * fraction) / 4096) in r0
 */
quantum_omap1710_mul_shift12:
    mov r2, r1, lsl #4
    smulwb r3, r0, r2
    mov r0, r3
    bx lr
.size quantum_omap1710_mul_shift12, .-quantum_omap1710_mul_shift12

.align 2
.global quantum_omap1710_mul_shift12_outer
.type quantum_omap1710_mul_shift12_outer,%function

/*
 * ARMv5TE correction for the outer Q12 remainder ranges. The caller
 * guarantees -4095 <= r1 <= -2049 or 2048 <= r1 <= 4095. Shifting by four
 * leaves the low halfword equal to the signed central-period fraction, so
 * SMLAWB needs only the integral +/-slope correction selected by the sign.
 *
 * r0 = slope, r1 = fraction
 * returns floor((slope * fraction) / 4096) in r0, with 32-bit wrap semantics
 */
quantum_omap1710_mul_shift12_outer:
    cmp r1, #0
    rsblt r3, r0, #0
    movge r3, r0
    mov r2, r1, lsl #4
    smlawb r3, r0, r2, r3
    mov r0, r3
    bx lr
.size quantum_omap1710_mul_shift12_outer, .-quantum_omap1710_mul_shift12_outer

.align 2
.global quantum_omap1710_udiv_u32
.type quantum_omap1710_udiv_u32,%function

/*
 * Normalized ARMv5TE unsigned scalar divide used by perspectiveDivide.
 *
 * r0 = unsigned dividend, r1 = unsigned nonzero divisor
 * returns floor(dividend / divisor) in r0. The caller handles divisor zero.
 * CLZ aligns the divisor to the dividend's top bit, so only quotient bits
 * that can be set are examined instead of always running a 32-step divide.
 */
quantum_omap1710_udiv_u32:
    cmp r1, #0
    beq .Ludiv_zero
    cmp r0, r1
    blo .Ludiv_zero

    stmdb sp!, {r4, r5, lr}
    clz r2, r1
    clz r3, r0
    sub r2, r2, r3
    mov r3, r1, lsl r2
    mov r4, #1
    mov r4, r4, lsl r2
    mov r5, #0
.Ludiv_loop:
    cmp r0, r3
    subcs r0, r0, r3
    orrcs r5, r5, r4
    movs r4, r4, lsr #1
    mov r3, r3, lsr #1
    bne .Ludiv_loop
    mov r0, r5
    ldmia sp!, {r4, r5, pc}
.Ludiv_zero:
    mov r0, #0
    bx lr
.size quantum_omap1710_udiv_u32, .-quantum_omap1710_udiv_u32

/*
 * Small DMODE 9 span kernels for the E65 renderer. Coordinates, shade state,
 * and the destination pointer stay in registers across each segment; texture
 * reads are indexed by the same wrapped 32-bit arithmetic as the C++ path.
 * The texture mask stays in a callee-saved register across each span, avoiding
 * a per-pixel stack reload. Destination stores remain sequential. No cache
 * maintenance or speculative prefetch instruction is used.
 *
 * Opaque and zero-transparent variants are separate so the common opaque
 * path pays no per-pixel transparency branch. The transparent variant does
 * not advance shade for zero texels, matching Java's DMODE 9 semantics.
 */
.macro QUANTUM_OMAP1710_MODE9_SPAN name, transparent
.align 2
.global \name
.type \name,%function
\name:
    stmdb sp!, {r3-r11, lr}
    sub sp, sp, #8
    str r0, [sp, #4]

    ldr r4, [r0, #0]       /* destination */
    ldr r5, [r0, #4]       /* texture */
    ldr r6, [r0, #8]       /* number of pixels */
    ldr r7, [r0, #12]      /* log2(texture width) */
    ldr r8, [r0, #16]      /* texture pixel mask; held across the loop */
    ldr r9, [r0, #20]      /* u bits */
    ldr r10, [r0, #24]     /* v bits */
    ldr r11, [r0, #28]     /* du bits */
    ldr r12, [r0, #32]     /* dv bits */
    mov lr, r12
    ldr r1, [r0, #36]      /* shade accumulator bits */
    ldr r2, [r0, #40]      /* shade slope bits */

    cmp r6, #0
    ble .Lmode9_done_\@
.Lmode9_loop_\@:
    add r9, r9, r11
    add r10, r10, lr
    mov r0, r10, lsr #12
    mov r0, r0, lsl r7
    add r0, r0, r9, lsr #12
    and r0, r0, r8
    ldr r12, [r5, r0, lsl #2]
.if \transparent
    cmp r12, #0
    beq .Lmode9_skip_\@
.endif

    add r1, r1, r2
    mov r3, r1, lsr #12
    /* Clear alpha and green to pack red/blue without a second temp register. */
    bic r0, r12, #0xff000000
    bic r0, r0, #0x0000ff00
#ifdef QUANTUM_LEVEL41_OMAP1710_MODE9_MUL_ORDER_EXPERIMENT
    /* ARM9's variable-latency MUL uses Rs as its multiplier. Shade is the
       smaller operand, so keep it in Rs rather than the packed texel word. */
    mul r0, r0, r3
#else
    mul r0, r3, r0
#endif
    and r12, r12, #0x0000ff00
#ifdef QUANTUM_LEVEL41_OMAP1710_MODE9_MUL_ORDER_EXPERIMENT
    mul r12, r12, r3
#else
    mul r12, r3, r12
#endif
    /* (redBlue >> 8) & ~0x0000ff00 == (redBlue & 0xff00ff00) >> 8. */
    mov r0, r0, lsr #8
    bic r0, r0, #0x0000ff00
    mov r12, r12, lsr #16
    and r12, r12, #0xff
    orr r12, r0, r12, lsl #8
    str r12, [r4], #4
.if \transparent
    b .Lmode9_next_\@
.Lmode9_skip_\@:
    add r4, r4, #4
.Lmode9_next_\@:
.endif
    subs r6, r6, #1
    bne .Lmode9_loop_\@

.Lmode9_done_\@:
    ldr r12, [sp, #4]
    str r9, [r12, #20]
    str r10, [r12, #24]
    str r1, [r12, #36]
    add sp, sp, #8
    ldmia sp!, {r3-r11, pc}
.size \name, .-\name
.endm

QUANTUM_OMAP1710_MODE9_SPAN quantum_omap1710_mode9_span_opaque, 0
QUANTUM_OMAP1710_MODE9_SPAN quantum_omap1710_mode9_span_transparent, 1
