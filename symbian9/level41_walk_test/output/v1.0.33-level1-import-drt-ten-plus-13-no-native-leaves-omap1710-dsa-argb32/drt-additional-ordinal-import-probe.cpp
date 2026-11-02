/* Retain static DRT function ordinals as data-only address references.
   The imports are used only to create GOT slots for the E32 import table; the
   retained probe is never called and it never invokes a DRT helper. The
   link-time proxy declares these ordinal exports as 4-byte DATA because the
   E32 converter supports imported-object GOT slots without call relocations. */
extern "C" {

extern const unsigned char quantumAeabiUnwindPr0[] __asm__("__aeabi_unwind_cpp_pr0");
extern const unsigned char quantumAeabiUnwindPr1[] __asm__("__aeabi_unwind_cpp_pr1");
extern const unsigned char quantumAeabiUnwindPr2[] __asm__("__aeabi_unwind_cpp_pr2");
extern const unsigned char quantumCxaBeginCatch[] __asm__("__cxa_begin_catch");
extern const unsigned char quantumCxaBeginCleanup[] __asm__("__cxa_begin_cleanup");
extern const unsigned char quantumCxaEndCatch[] __asm__("__cxa_end_catch");
extern const unsigned char quantumCxaEndCleanup[] __asm__("__cxa_end_cleanup");
extern const unsigned char quantumCppExceptionsGlobalsCtor[] __asm__("_ZN23TCppRTExceptionsGlobalsC1Ev");
extern const unsigned char quantumStdTerminate[] __asm__("_ZSt9terminatev");
extern const unsigned char quantumCxaTypeMatch[] __asm__("__cxa_type_match");
extern const unsigned char quantumUnwindVrsGet[] __asm__("_Unwind_VRS_Get");
extern const unsigned char quantumUnwindVrsSet[] __asm__("_Unwind_VRS_Set");

__attribute__((noinline, used))
void quantumDrtAdditional13AddressRetentionProbe()
    {
    const unsigned char* volatile p0 = quantumAeabiUnwindPr0;
    const unsigned char* volatile p1 = quantumAeabiUnwindPr1;
    const unsigned char* volatile p2 = quantumAeabiUnwindPr2;
    const unsigned char* volatile p3 = quantumCxaBeginCatch;
    const unsigned char* volatile p4 = quantumCxaBeginCleanup;
    const unsigned char* volatile p5 = quantumCxaEndCatch;
    const unsigned char* volatile p6 = quantumCxaEndCleanup;
    const unsigned char* volatile p7 = quantumCppExceptionsGlobalsCtor;
    const unsigned char* volatile p8 = quantumStdTerminate;
    const unsigned char* volatile p9 = quantumCxaTypeMatch;
    const unsigned char* volatile p10 = quantumUnwindVrsGet;
    const unsigned char* volatile p11 = quantumUnwindVrsSet;
    (void)p0; (void)p1; (void)p2; (void)p3; (void)p4; (void)p5;
    (void)p6; (void)p7; (void)p8; (void)p9; (void)p10; (void)p11;
}

volatile int quantumDrtAdditional13ImportProbeEnabled = 0;

} // extern "C"
