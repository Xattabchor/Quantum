.syntax unified
.arch armv5te
.arm
.text
.align 2
.global quantum_omap1710_blend_argb_row
.type quantum_omap1710_blend_argb_row,%function

/*
 * Composite one forward destination row from forward or reverse ARGB input.
 *
 * r0 = destination RGB row
 * r1 = first source ARGB pixel
 * r2 = pixel count
 * r3 = source step (+1 forward, -1 mirrored)
 *
 * Alpha zero skips, alpha 255 replaces, and partial alpha uses the exact
 * round-to-nearest divide-by-255 formula used by SoftwareWeaponRenderer.
 */
quantum_omap1710_blend_argb_row:
    stmdb sp!, {r4-r11, lr}
    cmp r2, #0
    ble .Lweapon_row_done
.Lweapon_row_loop:
    ldr r4, [r1]
    mov r6, r4, lsr #24
    cmp r6, #0
    beq .Lweapon_row_advance
    cmp r6, #255
    beq .Lweapon_row_opaque

    ldr r5, [r0]
    rsb r7, r6, #255

    /* Red: (src*a + dst*(255-a) + 128) / 255. */
    mov r8, r4, lsr #16
    and r8, r8, #255
    mov r9, r5, lsr #16
    and r9, r9, #255
    mul r10, r8, r6
    mla r10, r9, r7, r10
    add r10, r10, #128
    add r10, r10, r10, lsr #8
    mov r11, r10, lsr #8
    mov r11, r11, lsl #16

    /* Green. */
    mov r8, r4, lsr #8
    and r8, r8, #255
    mov r9, r5, lsr #8
    and r9, r9, #255
    mul r10, r8, r6
    mla r10, r9, r7, r10
    add r10, r10, #128
    add r10, r10, r10, lsr #8
    mov r8, r10, lsr #8
    and r8, r8, #255
    orr r11, r11, r8, lsl #8

    /* Blue. */
    and r8, r4, #255
    and r9, r5, #255
    mul r10, r8, r6
    mla r10, r9, r7, r10
    add r10, r10, #128
    add r10, r10, r10, lsr #8
    mov r12, r10, lsr #8
    orr r11, r11, r12
    str r11, [r0]
    b .Lweapon_row_advance

.Lweapon_row_opaque:
    mov r12, r4, lsl #8
    mov r12, r12, lsr #8
    str r12, [r0]

.Lweapon_row_advance:
    add r0, r0, #4
    add r1, r1, r3, lsl #2
    subs r2, r2, #1
    bne .Lweapon_row_loop
.Lweapon_row_done:
    ldmia sp!, {r4-r11, pc}
.size quantum_omap1710_blend_argb_row, .-quantum_omap1710_blend_argb_row

.align 2
.global quantum_omap1710_rain_streak
.type quantum_omap1710_rain_streak,%function

/*
 * Rasterize a clipped-safe shallow rain streak with the same integer
 * error/remainder recurrence as the C++ reference. The line is inclusive,
 * descends one row per step, and may move left by at most one pixel per row.
 *
 * r0 = Omap1710RainStreakArgs* (nine 32-bit words)
 */
quantum_omap1710_rain_streak:
    stmdb sp!, {r4-r11, lr}
    ldr r4, [r0, #0]       /* destination */
    ldr r5, [r0, #4]       /* destination stride */
    ldr r6, [r0, #8]       /* destination width */
    ldr r7, [r0, #12]      /* destination height */
    ldr r8, [r0, #16]      /* current x */
    ldr r9, [r0, #20]      /* current y */
    ldr r10, [r0, #24]     /* positive horizontal delta */
    ldr r11, [r0, #28]     /* positive vertical delta */
    ldr r2, [r0, #32]      /* opaque rain color */

    cmp r11, #0
    blt .Lrain_done
    cmp r10, #0
    blt .Lrain_done
    cmp r10, r11
    bhi .Lrain_done

    mov r3, r11, lsr #1   /* nearest-pixel error bias */
    mov r1, r11           /* remaining steps; draw both endpoints */
.Lrain_loop:
    cmp r9, #0
    blt .Lrain_skip_store
    cmp r9, r7
    bge .Lrain_skip_store
    cmp r8, #0
    blt .Lrain_skip_store
    cmp r8, r6
    bge .Lrain_skip_store
    mla r0, r9, r5, r8
    str r2, [r4, r0, lsl #2]
.Lrain_skip_store:
    cmp r1, #0
    beq .Lrain_done
    add r3, r3, r10
    cmp r3, r11
    subge r3, r3, r11
    subge r8, r8, #1
    add r9, r9, #1
    subs r1, r1, #1
    b .Lrain_loop
.Lrain_done:
    ldmia sp!, {r4-r11, pc}
.size quantum_omap1710_rain_streak, .-quantum_omap1710_rain_streak

.section .note.GNU-stack,"",%progbits
