# The Smart study

The scripts behind [the study in the main README](../../README.md#the-study-behind-smart).
They need Python with numpy, scipy, scikit-image, Pillow and numba.

- `panel.py`: the panel's pigments from its datasheet, the gamut they span, and a simulation of how a picture on the panel looks.
- `core2.py`: the reference version of Smart. `core.py` holds copies of the classic textures and the shared building blocks.
- `resample.py`: going from a photo to the panel's pixels, with the detail-preserving downscale.
- `bench2.py`: the benchmark.
- `figures.py`: the comparison pictures in `docs/smart`.

To repeat the benchmark on the app's own code:

1. Put test pictures in `tools/smart/in`, then run `python bench2.py synth` to add the ramps, the line art and three sample photographs in `in2`.
2. `python bench2.py dump out_dir` writes every picture the way the app's canvas hands it over.
3. Run the app's unit tests with `INKWELL_BENCH_DIR` set to that folder. `SmartTest.benchmark` runs every texture over the pictures and writes the results next to them.
4. `python bench2.py kotlin out_dir` scores them.
