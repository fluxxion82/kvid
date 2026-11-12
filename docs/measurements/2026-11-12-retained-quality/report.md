# Retrieval evaluation against bge-fp32@512 (raw queries)

Datasets: fiqa, nfcorpus, scifact. Metrics recomputed independently: maximum difference 4.4e-16; against trec_eval: 2.2e-16. 95% intervals from 10000 paired, query-resampled bootstraps (seed 20261108); margin 0.02.

| Run | Instruction | Hybrid nDCG@10 delta (95% CI) | Verdict | Semantic nDCG@10 delta (95% CI) | Hybrid recall@100 delta | Sets losing > margin (hybrid) |
|---|---|---|---|---|---|---|
| bge-fp32@128 | off | -0.0141 (-0.0214, -0.0067) | INCONCLUSIVE | -0.0348 (-0.0448, -0.0251) | -0.0062 (-0.0122, -0.0002) | fiqa |
| minilm-fp32@128 | off | -0.0157 (-0.0247, -0.0066) | INCONCLUSIVE | -0.0458 (-0.0592, -0.0321) | +0.0016 (-0.0082, +0.0112) | scifact |
| minilm-fp32@256 | off | -0.0122 (-0.0204, -0.0040) | INCONCLUSIVE | -0.0374 (-0.0499, -0.0250) | +0.0078 (-0.0019, +0.0173) | scifact |

## Per-set delta against the baseline (reported beside the rule, not part of it)

| Run | Instruction | Mode | fiqa delta (95% CI) | nfcorpus delta (95% CI) | scifact delta (95% CI) |
|---|---|---|---|---|---|
| bge-fp32@128 | off | hybrid nDCG@10 | -0.0245 (-0.0352, -0.0142) | -0.0096 (-0.0170, -0.0021) | -0.0082 (-0.0258, +0.0087) |
| bge-fp32@128 | off | semantic nDCG@10 | -0.0466 (-0.0607, -0.0327) | -0.0086 (-0.0195, +0.0023) | -0.0492 (-0.0734, -0.0265) |
| minilm-fp32@128 | off | hybrid nDCG@10 | +0.0010 (-0.0130, +0.0149) | -0.0129 (-0.0234, -0.0029) | -0.0354 (-0.0569, -0.0146) |
| minilm-fp32@128 | off | semantic nDCG@10 | -0.0186 (-0.0383, +0.0012) | -0.0226 (-0.0379, -0.0071) | -0.0961 (-0.1285, -0.0650) |
| minilm-fp32@256 | off | hybrid nDCG@10 | +0.0029 (-0.0090, +0.0150) | -0.0103 (-0.0209, +0.0002) | -0.0292 (-0.0476, -0.0110) |
| minilm-fp32@256 | off | semantic nDCG@10 | -0.0162 (-0.0344, +0.0015) | -0.0211 (-0.0365, -0.0059) | -0.0749 (-0.1027, -0.0475) |

## Absolute scores

| Dataset | Run | Instruction | Mode | nDCG@10 | Recall@10 | Recall@100 | ms/doc | Truncated |
|---|---|---|---|---|---|---|---|---|
| fiqa | bge-fp32@128 | off | hybrid | 0.3225 | 0.4054 | 0.6642 | 16.64 | 0.4684 |
| fiqa | bge-fp32@128 | off | semantic | 0.3383 | 0.4008 | 0.6326 | 16.64 | 0.4684 |
| fiqa | bge-fp32@512 | off | hybrid | 0.3469 | 0.4230 | 0.6797 | 31.66 | 0.042 |
| fiqa | bge-fp32@512 | off | semantic | 0.3848 | 0.4396 | 0.6866 | 31.66 | 0.042 |
| fiqa | lexical | - | lexical | 0.2333 | 0.2918 | 0.5144 | - | - |
| fiqa | minilm-fp32@128 | off | hybrid | 0.3479 | 0.4272 | 0.6884 | 9.02 | 0.4684 |
| fiqa | minilm-fp32@128 | off | semantic | 0.3663 | 0.4265 | 0.6939 | 9.02 | 0.4684 |
| fiqa | minilm-fp32@256 | off | hybrid | 0.3498 | 0.4302 | 0.6985 | 12.99 | 0.1917 |
| fiqa | minilm-fp32@256 | off | semantic | 0.3687 | 0.4413 | 0.7061 | 12.99 | 0.1917 |
| nfcorpus | bge-fp32@128 | off | hybrid | 0.3422 | 0.1679 | 0.3049 | 19.2 | 0.9736 |
| nfcorpus | bge-fp32@128 | off | semantic | 0.3285 | 0.1578 | 0.2876 | 19.2 | 0.9736 |
| nfcorpus | bge-fp32@512 | off | hybrid | 0.3518 | 0.1705 | 0.3148 | 60.12 | 0.0908 |
| nfcorpus | bge-fp32@512 | off | semantic | 0.3371 | 0.1582 | 0.3058 | 60.12 | 0.0908 |
| nfcorpus | lexical | - | lexical | 0.3038 | 0.1472 | 0.2321 | - | - |
| nfcorpus | minilm-fp32@128 | off | hybrid | 0.3389 | 0.1683 | 0.3230 | 9.13 | 0.9736 |
| nfcorpus | minilm-fp32@128 | off | semantic | 0.3145 | 0.1537 | 0.3094 | 9.13 | 0.9736 |
| nfcorpus | minilm-fp32@256 | off | hybrid | 0.3415 | 0.1681 | 0.3251 | 18.32 | 0.7883 |
| nfcorpus | minilm-fp32@256 | off | semantic | 0.3159 | 0.1550 | 0.3115 | 18.32 | 0.7883 |
| scifact | bge-fp32@128 | off | hybrid | 0.7083 | 0.8204 | 0.9717 | 17.51 | 0.9788 |
| scifact | bge-fp32@128 | off | semantic | 0.6708 | 0.7946 | 0.9383 | 17.51 | 0.9788 |
| scifact | bge-fp32@512 | off | hybrid | 0.7164 | 0.8337 | 0.9650 | 54.51 | 0.0878 |
| scifact | bge-fp32@512 | off | semantic | 0.7200 | 0.8452 | 0.9533 | 54.51 | 0.0878 |
| scifact | lexical | - | lexical | 0.6616 | 0.7919 | 0.8809 | - | - |
| scifact | minilm-fp32@128 | off | hybrid | 0.6810 | 0.8196 | 0.9527 | 9.42 | 0.9788 |
| scifact | minilm-fp32@128 | off | semantic | 0.6239 | 0.7743 | 0.9227 | 9.42 | 0.9788 |
| scifact | minilm-fp32@256 | off | hybrid | 0.6872 | 0.8186 | 0.9593 | 19.43 | 0.7102 |
| scifact | minilm-fp32@256 | off | semantic | 0.6451 | 0.7833 | 0.9250 | 19.43 | 0.7102 |

## Hybrid against lexical (per set, paired)

| Dataset | Run | Instruction | Hybrid - lexical nDCG@10 (95% CI) |
|---|---|---|---|
| fiqa | bge-fp32@512 | off | +0.1136 (+0.0975, +0.1302) |
| nfcorpus | bge-fp32@512 | off | +0.0480 (+0.0338, +0.0629) |
| scifact | bge-fp32@512 | off | +0.0548 (+0.0312, +0.0794) |
| fiqa | bge-fp32@128 | off | +0.0892 (+0.0715, +0.1076) |
| nfcorpus | bge-fp32@128 | off | +0.0384 (+0.0237, +0.0538) |
| scifact | bge-fp32@128 | off | +0.0466 (+0.0195, +0.0739) |
| fiqa | minilm-fp32@128 | off | +0.1147 (+0.0970, +0.1319) |
| nfcorpus | minilm-fp32@128 | off | +0.0351 (+0.0213, +0.0490) |
| scifact | minilm-fp32@128 | off | +0.0194 (-0.0070, +0.0456) |
| fiqa | minilm-fp32@256 | off | +0.1165 (+0.0998, +0.1330) |
| nfcorpus | minilm-fp32@256 | off | +0.0377 (+0.0239, +0.0518) |
| scifact | minilm-fp32@256 | off | +0.0256 (+0.0023, +0.0488) |

## Hybrid nDCG@10 by the length of the longest relevant document (tokens)

| Dataset | Run | Instruction | <=128 (n) | 129-256 (n) | 257-512 (n) | >512 (n) |
|---|---|---|---|---|---|---|
| fiqa | bge-fp32@512 | off | 0.305 (86) | 0.315 (201) | 0.372 (253) | 0.382 (108) |
| fiqa | bge-fp32@128 | off | 0.312 (86) | 0.319 (201) | 0.328 (253) | 0.324 (108) |
| fiqa | minilm-fp32@128 | off | 0.307 (86) | 0.335 (201) | 0.366 (253) | 0.361 (108) |
| fiqa | minilm-fp32@256 | off | 0.297 (86) | 0.338 (201) | 0.374 (253) | 0.359 (108) |
| nfcorpus | bge-fp32@512 | off | - | 0.477 (5) | 0.304 (95) | 0.370 (223) |
| nfcorpus | bge-fp32@128 | off | - | 0.477 (5) | 0.298 (95) | 0.358 (223) |
| nfcorpus | minilm-fp32@128 | off | - | 0.463 (5) | 0.299 (95) | 0.353 (223) |
| nfcorpus | minilm-fp32@256 | off | - | 0.463 (5) | 0.298 (95) | 0.358 (223) |
| scifact | bge-fp32@512 | off | 0.494 (2) | 0.624 (73) | 0.747 (175) | 0.752 (50) |
| scifact | bge-fp32@128 | off | 0.531 (2) | 0.645 (73) | 0.726 (175) | 0.744 (50) |
| scifact | minilm-fp32@128 | off | 0.565 (2) | 0.621 (73) | 0.701 (175) | 0.705 (50) |
| scifact | minilm-fp32@256 | off | 0.531 (2) | 0.611 (73) | 0.719 (175) | 0.693 (50) |

## Top-10 overlap with the reference run (semantic, raw queries)

| Dataset | Run | Reference | Mean overlap@10 |
|---|---|---|---|
| fiqa | bge-fp32@128 | bge-fp32@512 | 0.737 |
| nfcorpus | bge-fp32@128 | bge-fp32@512 | 0.617 |
| scifact | bge-fp32@128 | bge-fp32@512 | 0.660 |
| fiqa | minilm-fp32@128 | minilm-fp32@256 | 0.724 |
| nfcorpus | minilm-fp32@128 | minilm-fp32@256 | 0.693 |
| scifact | minilm-fp32@128 | minilm-fp32@256 | 0.716 |
