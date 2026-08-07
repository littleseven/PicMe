"""Static type-checking smoke test for the bundled stubs.

The body is wrapped in a function that is never called, so importing this
module has no runtime side effects (it is only fed to a type checker such as
pyright or mypy). If a stubbed symbol is removed or renamed, type checking
fails. This guards the public surface described in
``sentencepiece/__init__.pyi``.
"""

import sentencepiece as spm


def _check_public_api() -> None:
  sp = spm.SentencePieceProcessor(model_file="x.model")

  ids = sp.encode("hello", out_type=int)
  pieces = sp.encode("hello", out_type=str)
  text = sp.decode(ids)

  # CamelCase and snake_case aliases both resolve.
  ids2 = sp.EncodeAsIds("hello")
  text2 = sp.decode_ids([1, 2, 3])
  tok = sp.tokenize("hello")
  detok = sp.detokenize([1, 2, 3])

  # Vocabulary helpers.
  size: int = sp.get_piece_size()
  piece: str = sp.IdToPiece(0)
  pid: int = sp.PieceToId("a")
  unk: int = sp.unk_id()

  # Parallel encoding API (added in v0.2.2).
  pool = spm.ThreadPool(4)
  par_ids = sp.parallel_encode("hello world", chunk_len=8, num_threads=2)
  par_pieces = sp.ParallelEncodeAsPieces(["a", "b"], chunk_len=4, thread_pool=pool)

  # Normalizer.
  norm = spm.SentencePieceNormalizer(model_file="x.model")
  normalized = norm.normalize("hello")


def _check_module_level() -> None:
  # Trainer and module-level helpers.
  spm.SentencePieceTrainer.train(input="x.txt", model_prefix="m", vocab_size=10)
  spm.set_min_log_level(1)
  spm.SetRandomGeneratorSeed(42)
