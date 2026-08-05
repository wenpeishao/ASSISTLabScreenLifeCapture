// Placeholder translation unit for the standard (production) flavor.
// The real VLM native stack (vlm_bridge + llama.cpp) is only built when
// -DVLM_ENABLED=ON is passed by the mindpulseDev flavor. The resulting
// libvlm_noop.so is excluded from packaging in build.gradle.
extern "C" int screenomics_vlm_noop(void) { return 0; }
