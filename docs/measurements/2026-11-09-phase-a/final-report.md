# Retrieval evaluation against bge-fp32@512 (raw queries)

Datasets: fiqa, nfcorpus, scifact. Metrics recomputed independently: maximum difference 4.4e-16; against trec_eval: 2.2e-16. 95% intervals from 10000 paired, query-resampled bootstraps (seed 20261108); margin 0.02.

| Run | Instruction | Hybrid nDCG@10 delta (95% CI) | Verdict | Semantic nDCG@10 delta (95% CI) | Hybrid recall@100 delta | Sets losing > margin (hybrid) |
|---|---|---|---|---|---|---|
| bge-fp32@256 | off | -0.0019 (-0.0063, +0.0023) | PASS | -0.0069 (-0.0124, -0.0015) | +0.0004 (-0.0031, +0.0043) | - |
| bge-int8-all@512 | off | -0.0028 (-0.0070, +0.0016) | PASS | -0.0173 (-0.0236, -0.0111) | -0.0087 (-0.0142, -0.0036) | - |
| bge-int8@512 | off | -0.0032 (-0.0060, -0.0005) | PASS | -0.0003 (-0.0036, +0.0029) | +0.0024 (-0.0011, +0.0065) | - |
| minilm-fp32@256 | off | -0.0122 (-0.0205, -0.0041) | INCONCLUSIVE | -0.0374 (-0.0495, -0.0250) | +0.0078 (-0.0019, +0.0177) | scifact |
| minilm-int8@256 | off | -0.0121 (-0.0203, -0.0040) | INCONCLUSIVE | -0.0398 (-0.0527, -0.0271) | +0.0088 (-0.0007, +0.0188) | scifact |

## Absolute scores

| Dataset | Run | Instruction | Mode | nDCG@10 | Recall@10 | Recall@100 | ms/doc | Truncated |
|---|---|---|---|---|---|---|---|---|
| fiqa | bge-fp32@256 | off | hybrid | 0.3455 | 0.4260 | 0.6775 | 22.99 | 0.1917 |
| fiqa | bge-fp32@256 | off | semantic | 0.3781 | 0.4289 | 0.6788 | 22.99 | 0.1917 |
| fiqa | bge-fp32@512 | off | hybrid | 0.3469 | 0.4230 | 0.6797 | 31.66 | 0.042 |
| fiqa | bge-fp32@512 | off | semantic | 0.3848 | 0.4396 | 0.6866 | 31.66 | 0.042 |
| fiqa | bge-int8-all@512 | off | hybrid | 0.3411 | 0.4078 | 0.6618 | 17.2 | 0.042 |
| fiqa | bge-int8-all@512 | off | semantic | 0.3585 | 0.4236 | 0.6594 | 17.2 | 0.042 |
| fiqa | bge-int8@512 | off | hybrid | 0.3460 | 0.4212 | 0.6806 | 17.74 | 0.042 |
| fiqa | bge-int8@512 | off | semantic | 0.3851 | 0.4432 | 0.6885 | 17.74 | 0.042 |
| fiqa | lexical | - | lexical | 0.2333 | 0.2918 | 0.5144 | - | - |
| fiqa | minilm-fp32@256 | off | hybrid | 0.3498 | 0.4302 | 0.6985 | 12.99 | 0.1917 |
| fiqa | minilm-fp32@256 | off | semantic | 0.3687 | 0.4413 | 0.7061 | 12.99 | 0.1917 |
| fiqa | minilm-int8@256 | off | hybrid | 0.3507 | 0.4344 | 0.7003 | 9.63 | 0.1917 |
| fiqa | minilm-int8@256 | off | semantic | 0.3588 | 0.4303 | 0.7045 | 9.63 | 0.1917 |
| nfcorpus | bge-fp32@256 | off | hybrid | 0.3480 | 0.1707 | 0.3150 | 36.76 | 0.7883 |
| nfcorpus | bge-fp32@256 | off | semantic | 0.3421 | 0.1620 | 0.2986 | 36.76 | 0.7883 |
| nfcorpus | bge-fp32@512 | off | hybrid | 0.3518 | 0.1705 | 0.3148 | 60.12 | 0.0908 |
| nfcorpus | bge-fp32@512 | off | semantic | 0.3371 | 0.1582 | 0.3058 | 60.12 | 0.0908 |
| nfcorpus | bge-int8-all@512 | off | hybrid | 0.3498 | 0.1705 | 0.3099 | 31.99 | 0.0908 |
| nfcorpus | bge-int8-all@512 | off | semantic | 0.3306 | 0.1547 | 0.3027 | 31.99 | 0.0908 |
| nfcorpus | bge-int8@512 | off | hybrid | 0.3503 | 0.1694 | 0.3178 | 32.92 | 0.0908 |
| nfcorpus | bge-int8@512 | off | semantic | 0.3376 | 0.1573 | 0.3063 | 32.92 | 0.0908 |
| nfcorpus | lexical | - | lexical | 0.3038 | 0.1472 | 0.2321 | - | - |
| nfcorpus | minilm-fp32@256 | off | hybrid | 0.3415 | 0.1681 | 0.3251 | 18.32 | 0.7883 |
| nfcorpus | minilm-fp32@256 | off | semantic | 0.3159 | 0.1550 | 0.3115 | 18.32 | 0.7883 |
| nfcorpus | minilm-int8@256 | off | hybrid | 0.3423 | 0.1663 | 0.3213 | 13.52 | 0.7883 |
| nfcorpus | minilm-int8@256 | off | semantic | 0.3166 | 0.1554 | 0.3073 | 13.52 | 0.7883 |
| scifact | bge-fp32@256 | off | hybrid | 0.7157 | 0.8312 | 0.9683 | 34.9 | 0.7102 |
| scifact | bge-fp32@256 | off | semantic | 0.7009 | 0.8255 | 0.9500 | 34.9 | 0.7102 |
| scifact | bge-fp32@512 | off | hybrid | 0.7164 | 0.8337 | 0.9650 | 54.51 | 0.0878 |
| scifact | bge-fp32@512 | off | semantic | 0.7200 | 0.8452 | 0.9533 | 54.51 | 0.0878 |
| scifact | bge-int8-all@512 | off | hybrid | 0.7160 | 0.8327 | 0.9617 | 30.73 | 0.0878 |
| scifact | bge-int8-all@512 | off | semantic | 0.7010 | 0.8202 | 0.9433 | 30.73 | 0.0878 |
| scifact | bge-int8@512 | off | hybrid | 0.7093 | 0.8304 | 0.9683 | 30.9 | 0.0878 |
| scifact | bge-int8@512 | off | semantic | 0.7183 | 0.8446 | 0.9517 | 30.9 | 0.0878 |
| scifact | lexical | - | lexical | 0.6616 | 0.7919 | 0.8809 | - | - |
| scifact | minilm-fp32@256 | off | hybrid | 0.6872 | 0.8186 | 0.9593 | 19.43 | 0.7102 |
| scifact | minilm-fp32@256 | off | semantic | 0.6451 | 0.7833 | 0.9250 | 19.43 | 0.7102 |
| scifact | minilm-int8@256 | off | hybrid | 0.6859 | 0.8177 | 0.9643 | 13.68 | 0.7102 |
| scifact | minilm-int8@256 | off | semantic | 0.6470 | 0.7867 | 0.9283 | 13.68 | 0.7102 |

## Hybrid against lexical (per set, paired)

| Dataset | Run | Instruction | Hybrid - lexical nDCG@10 (95% CI) |
|---|---|---|---|
| fiqa | bge-fp32@512 | off | +0.1136 (+0.0971, +0.1302) |
| nfcorpus | bge-fp32@512 | off | +0.0480 (+0.0335, +0.0629) |
| scifact | bge-fp32@512 | off | +0.0548 (+0.0312, +0.0790) |
| fiqa | bge-fp32@256 | off | +0.1123 (+0.0956, +0.1288) |
| nfcorpus | bge-fp32@256 | off | +0.0442 (+0.0302, +0.0593) |
| scifact | bge-fp32@256 | off | +0.0540 (+0.0304, +0.0777) |
| fiqa | bge-int8-all@512 | off | +0.1078 (+0.0908, +0.1251) |
| nfcorpus | bge-int8-all@512 | off | +0.0460 (+0.0318, +0.0607) |
| scifact | bge-int8-all@512 | off | +0.0544 (+0.0301, +0.0787) |
| fiqa | bge-int8@512 | off | +0.1127 (+0.0964, +0.1290) |
| nfcorpus | bge-int8@512 | off | +0.0465 (+0.0327, +0.0614) |
| scifact | bge-int8@512 | off | +0.0477 (+0.0249, +0.0711) |
| fiqa | minilm-fp32@256 | off | +0.1165 (+0.1002, +0.1331) |
| nfcorpus | minilm-fp32@256 | off | +0.0377 (+0.0241, +0.0516) |
| scifact | minilm-fp32@256 | off | +0.0256 (+0.0026, +0.0485) |
| fiqa | minilm-int8@256 | off | +0.1175 (+0.1016, +0.1336) |
| nfcorpus | minilm-int8@256 | off | +0.0385 (+0.0249, +0.0523) |
| scifact | minilm-int8@256 | off | +0.0242 (-0.0001, +0.0481) |

## Hybrid nDCG@10 by the length of the longest relevant document (tokens)

| Dataset | Run | Instruction | <=128 (n) | 129-256 (n) | 257-512 (n) | >512 (n) |
|---|---|---|---|---|---|---|
| fiqa | bge-fp32@512 | off | 0.305 (86) | 0.315 (201) | 0.372 (253) | 0.382 (108) |
| fiqa | bge-fp32@256 | off | 0.301 (86) | 0.321 (201) | 0.373 (253) | 0.362 (108) |
| fiqa | bge-int8-all@512 | off | 0.314 (86) | 0.315 (201) | 0.363 (253) | 0.360 (108) |
| fiqa | bge-int8@512 | off | 0.298 (86) | 0.317 (201) | 0.371 (253) | 0.380 (108) |
| fiqa | minilm-fp32@256 | off | 0.297 (86) | 0.338 (201) | 0.374 (253) | 0.359 (108) |
| fiqa | minilm-int8@256 | off | 0.296 (86) | 0.338 (201) | 0.377 (253) | 0.357 (108) |
| nfcorpus | bge-fp32@512 | off | - | 0.477 (5) | 0.304 (95) | 0.370 (223) |
| nfcorpus | bge-fp32@256 | off | - | 0.486 (5) | 0.303 (95) | 0.364 (223) |
| nfcorpus | bge-int8-all@512 | off | - | 0.477 (5) | 0.306 (95) | 0.366 (223) |
| nfcorpus | bge-int8@512 | off | - | 0.477 (5) | 0.302 (95) | 0.368 (223) |
| nfcorpus | minilm-fp32@256 | off | - | 0.463 (5) | 0.298 (95) | 0.358 (223) |
| nfcorpus | minilm-int8@256 | off | - | 0.463 (5) | 0.301 (95) | 0.357 (223) |
| scifact | bge-fp32@512 | off | 0.494 (2) | 0.624 (73) | 0.747 (175) | 0.752 (50) |
| scifact | bge-fp32@256 | off | 0.509 (2) | 0.635 (73) | 0.745 (175) | 0.738 (50) |
| scifact | bge-int8-all@512 | off | 0.466 (2) | 0.646 (73) | 0.739 (175) | 0.746 (50) |
| scifact | bge-int8@512 | off | 0.494 (2) | 0.620 (73) | 0.739 (175) | 0.745 (50) |
| scifact | minilm-fp32@256 | off | 0.531 (2) | 0.611 (73) | 0.719 (175) | 0.693 (50) |
| scifact | minilm-int8@256 | off | 0.565 (2) | 0.627 (73) | 0.713 (175) | 0.683 (50) |

## Top-10 overlap with the reference run (semantic, raw queries)

| Dataset | Run | Reference | Mean overlap@10 |
|---|---|---|---|
| fiqa | bge-fp32@256 | bge-fp32@512 | 0.906 |
| nfcorpus | bge-fp32@256 | bge-fp32@512 | 0.807 |
| scifact | bge-fp32@256 | bge-fp32@512 | 0.839 |
| fiqa | bge-int8-all@512 | bge-fp32@512 | 0.706 |
| nfcorpus | bge-int8-all@512 | bge-fp32@512 | 0.742 |
| scifact | bge-int8-all@512 | bge-fp32@512 | 0.785 |
| fiqa | bge-int8@512 | bge-fp32@512 | 0.884 |
| nfcorpus | bge-int8@512 | bge-fp32@512 | 0.905 |
| scifact | bge-int8@512 | bge-fp32@512 | 0.912 |
| fiqa | minilm-int8@256 | minilm-fp32@256 | 0.854 |
| nfcorpus | minilm-int8@256 | minilm-fp32@256 | 0.875 |
| scifact | minilm-int8@256 | minilm-fp32@256 | 0.882 |
