/* The class_type_info vtable is a data export (DRT ordinal 123). Compile this
   isolated reference as PIC so it remains an undefined object import rather
   than an executable copy relocation. The gate in level41_main.cc defaults
   false, so this function does not run in the launch-only control. */
extern "C" {
extern const unsigned char quantumClassTypeInfoVtable[]
    __asm__("_ZTVN10__cxxabiv117__class_type_infoE");

void quantumDrtClassTypeInfoImportProbeMaybeRun()
    {
    const unsigned char* volatile classInfoVtable =
        quantumClassTypeInfoVtable;
    (void)classInfoVtable;
    }

} // extern "C"
