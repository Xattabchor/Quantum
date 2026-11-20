.syntax unified
.arch armv5te
.arm
.text
.align 2
.global quantum_omap1710_copy_argb8888_to_rgb565
.type quantum_omap1710_copy_argb8888_to_rgb565,%function

/*
 * Convert packed 0xAARRGGBB framebuffer pixels to little-endian RGB565.
 *
 * r0 = source QInt32 pixels
 * r1 = destination bytes (two pixels per 32-bit word)
 * r2 = number of pixel pairs (the caller checks even pixel count/alignment)
 *
 * The channel masks and truncation match storeRgb888ToEColor64K exactly.
 * This is isolated to the E65/OMAP1710 EColor64K target; the general
 * Symbian ARMV5 profile and all other presenter paths remain C++.
 */
quantum_omap1710_copy_argb8888_to_rgb565:
    stmdb sp!, {r4-r10, lr}
    mov r4, r0                  /* source */
    mov r5, r1                  /* destination */
    mov r6, r2                  /* pair count */
    cmp r6, #0
    ble .Lcopy_done

.Lcopy_pair_loop:
    ldmia r4!, {r7, r8}         /* two 0xAARRGGBB pixels */

    and r9, r7, #0x00f80000
    mov r9, r9, lsr #8          /* red: bits 23..19 -> 15..11 */
    and r10, r7, #0x0000fc00
    orr r9, r9, r10, lsr #5    /* green: bits 15..10 -> 10..5 */
    and r10, r7, #0x000000f8
    orr r9, r9, r10, lsr #3    /* blue: bits 7..3 -> 4..0 */

    and r10, r8, #0x00f80000
    mov r10, r10, lsr #8
    and r12, r8, #0x0000fc00
    orr r10, r10, r12, lsr #5
    and r12, r8, #0x000000f8
    orr r10, r10, r12, lsr #3

    orr r9, r9, r10, lsl #16   /* pack two RGB565 halfwords */
    str r9, [r5], #4
    subs r6, r6, #1
    bne .Lcopy_pair_loop

.Lcopy_done:
    ldmia sp!, {r4-r10, lr}
    bx lr
.size quantum_omap1710_copy_argb8888_to_rgb565, .-quantum_omap1710_copy_argb8888_to_rgb565
