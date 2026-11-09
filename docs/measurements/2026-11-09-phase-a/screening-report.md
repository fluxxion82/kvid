# Retrieval evaluation against bge-fp32@512 (raw queries)

Datasets: fiqa, nfcorpus, scifact. Metrics recomputed independently: maximum difference 4.4e-16; against trec_eval: 2.2e-16. 95% intervals from 10000 paired, query-resampled bootstraps (seed 20261108); margin 0.02.

| Run | Instruction | Hybrid nDCG@10 delta (95% CI) | Verdict | Semantic nDCG@10 delta (95% CI) | Hybrid recall@100 delta | Sets losing > margin (hybrid) |
|---|---|---|---|---|---|---|
| bge-fp32-opt@512 | off | +0.0000 (+0.0000, +0.0000) | PASS | +0.0000 (+0.0000, +0.0000) | +0.0000 (+0.0000, +0.0000) | - |
| bge-fp32-opt@512 | on | -0.0017 (-0.0056, +0.0023) | PASS | +0.0059 (+0.0000, +0.0118) | -0.0029 (-0.0086, +0.0028) | - |
| bge-fp32@128 | off | -0.0139 (-0.0216, -0.0065) | INCONCLUSIVE | -0.0341 (-0.0442, -0.0242) | -0.0059 (-0.0116, -0.0001) | fiqa |
| bge-fp32@128 | on | -0.0150 (-0.0228, -0.0073) | INCONCLUSIVE | -0.0252 (-0.0360, -0.0146) | -0.0058 (-0.0120, +0.0006) | fiqa |
| bge-fp32@256 | off | -0.0022 (-0.0066, +0.0022) | PASS | -0.0076 (-0.0130, -0.0022) | -0.0003 (-0.0034, +0.0034) | - |
| bge-fp32@256 | on | -0.0061 (-0.0112, -0.0012) | PASS | -0.0001 (-0.0077, +0.0075) | -0.0013 (-0.0071, +0.0044) | - |
| bge-fp32@512 | on | -0.0017 (-0.0056, +0.0022) | PASS | +0.0059 (+0.0003, +0.0116) | -0.0029 (-0.0087, +0.0029) | - |
| bge-int8-all@512 | off | -0.0040 (-0.0082, +0.0002) | PASS | -0.0169 (-0.0229, -0.0109) | -0.0055 (-0.0103, -0.0010) | - |
| bge-int8-all@512 | on | -0.0065 (-0.0112, -0.0019) | PASS | -0.0136 (-0.0205, -0.0066) | -0.0048 (-0.0110, +0.0014) | - |
| bge-int8@512 | off | -0.0028 (-0.0057, -0.0001) | PASS | -0.0001 (-0.0033, +0.0030) | +0.0022 (-0.0013, +0.0061) | - |
| bge-int8@512 | on | -0.0016 (-0.0060, +0.0027) | PASS | +0.0047 (-0.0013, +0.0108) | +0.0019 (-0.0031, +0.0071) | - |
| minilm-fp32-opt@256 | off | -0.0113 (-0.0190, -0.0034) | PASS | -0.0321 (-0.0447, -0.0200) | +0.0081 (-0.0017, +0.0179) | scifact |
| minilm-fp32@128 | off | -0.0173 (-0.0262, -0.0083) | INCONCLUSIVE | -0.0447 (-0.0583, -0.0312) | +0.0042 (-0.0053, +0.0137) | scifact |
| minilm-fp32@256 | off | -0.0113 (-0.0193, -0.0032) | PASS | -0.0321 (-0.0443, -0.0202) | +0.0081 (-0.0013, +0.0176) | scifact |
| minilm-int8@256 | off | -0.0115 (-0.0195, -0.0037) | PASS | -0.0338 (-0.0466, -0.0212) | +0.0065 (-0.0031, +0.0159) | scifact |

## Absolute scores

| Dataset | Run | Instruction | Mode | nDCG@10 | Recall@10 | Recall@100 | ms/doc | Truncated |
|---|---|---|---|---|---|---|---|---|
| fiqa | bge-fp32-opt@512 | off | hybrid | 0.4371 | 0.5083 | 0.7768 | 29.37 | 0.0423 |
| fiqa | bge-fp32-opt@512 | off | semantic | 0.4681 | 0.5365 | 0.7902 | 29.37 | 0.0423 |
| fiqa | bge-fp32-opt@512 | on | hybrid | 0.4310 | 0.4987 | 0.7720 | 29.37 | 0.0423 |
| fiqa | bge-fp32-opt@512 | on | semantic | 0.4871 | 0.5520 | 0.7857 | 29.37 | 0.0423 |
| fiqa | bge-fp32@128 | off | hybrid | 0.4131 | 0.4870 | 0.7623 | 15.47 | 0.4791 |
| fiqa | bge-fp32@128 | off | semantic | 0.4236 | 0.4870 | 0.7473 | 15.47 | 0.4791 |
| fiqa | bge-fp32@128 | on | hybrid | 0.4065 | 0.4769 | 0.7640 | 15.47 | 0.4791 |
| fiqa | bge-fp32@128 | on | semantic | 0.4395 | 0.5047 | 0.7478 | 15.47 | 0.4791 |
| fiqa | bge-fp32@256 | off | hybrid | 0.4349 | 0.5052 | 0.7723 | 22.96 | 0.2018 |
| fiqa | bge-fp32@256 | off | semantic | 0.4594 | 0.5254 | 0.7771 | 22.96 | 0.2018 |
| fiqa | bge-fp32@256 | on | hybrid | 0.4251 | 0.4978 | 0.7747 | 22.96 | 0.2018 |
| fiqa | bge-fp32@256 | on | semantic | 0.4811 | 0.5486 | 0.7815 | 22.96 | 0.2018 |
| fiqa | bge-fp32@512 | off | hybrid | 0.4371 | 0.5083 | 0.7768 | 31.71 | 0.0423 |
| fiqa | bge-fp32@512 | off | semantic | 0.4681 | 0.5365 | 0.7902 | 31.71 | 0.0423 |
| fiqa | bge-fp32@512 | on | hybrid | 0.4310 | 0.4987 | 0.7720 | 31.71 | 0.0423 |
| fiqa | bge-fp32@512 | on | semantic | 0.4871 | 0.5520 | 0.7857 | 31.71 | 0.0423 |
| fiqa | bge-int8-all@512 | off | hybrid | 0.4274 | 0.4914 | 0.7684 | 16.73 | 0.0423 |
| fiqa | bge-int8-all@512 | off | semantic | 0.4429 | 0.5133 | 0.7709 | 16.73 | 0.0423 |
| fiqa | bge-int8-all@512 | on | hybrid | 0.4260 | 0.4966 | 0.7670 | 16.73 | 0.0423 |
| fiqa | bge-int8-all@512 | on | semantic | 0.4569 | 0.5233 | 0.7760 | 16.73 | 0.0423 |
| fiqa | bge-int8@512 | off | hybrid | 0.4372 | 0.5074 | 0.7769 | 17.58 | 0.0423 |
| fiqa | bge-int8@512 | off | semantic | 0.4689 | 0.5331 | 0.7886 | 17.58 | 0.0423 |
| fiqa | bge-int8@512 | on | hybrid | 0.4321 | 0.5015 | 0.7784 | 17.58 | 0.0423 |
| fiqa | bge-int8@512 | on | semantic | 0.4827 | 0.5527 | 0.7797 | 17.58 | 0.0423 |
| fiqa | lexical | - | lexical | 0.3092 | 0.3753 | 0.6081 | - | - |
| fiqa | minilm-fp32-opt@256 | off | hybrid | 0.4428 | 0.5115 | 0.7964 | 11.47 | 0.2018 |
| fiqa | minilm-fp32-opt@256 | off | semantic | 0.4680 | 0.5458 | 0.8049 | 11.47 | 0.2018 |
| fiqa | minilm-fp32@128 | off | hybrid | 0.4335 | 0.5065 | 0.7934 | 8.07 | 0.4791 |
| fiqa | minilm-fp32@128 | off | semantic | 0.4528 | 0.5273 | 0.7890 | 8.07 | 0.4791 |
| fiqa | minilm-fp32@256 | off | hybrid | 0.4428 | 0.5115 | 0.7964 | 11.59 | 0.2018 |
| fiqa | minilm-fp32@256 | off | semantic | 0.4680 | 0.5458 | 0.8049 | 11.59 | 0.2018 |
| fiqa | minilm-int8@256 | off | hybrid | 0.4427 | 0.5138 | 0.7905 | 9.1 | 0.2018 |
| fiqa | minilm-int8@256 | off | semantic | 0.4602 | 0.5407 | 0.8102 | 9.1 | 0.2018 |
| nfcorpus | bge-fp32-opt@512 | off | hybrid | 0.3518 | 0.1705 | 0.3148 | 55.68 | 0.0908 |
| nfcorpus | bge-fp32-opt@512 | off | semantic | 0.3371 | 0.1582 | 0.3058 | 55.68 | 0.0908 |
| nfcorpus | bge-fp32-opt@512 | on | hybrid | 0.3538 | 0.1714 | 0.3126 | 55.68 | 0.0908 |
| nfcorpus | bge-fp32-opt@512 | on | semantic | 0.3430 | 0.1620 | 0.3110 | 55.68 | 0.0908 |
| nfcorpus | bge-fp32@128 | off | hybrid | 0.3422 | 0.1679 | 0.3049 | 19.2 | 0.9736 |
| nfcorpus | bge-fp32@128 | off | semantic | 0.3285 | 0.1578 | 0.2876 | 19.2 | 0.9736 |
| nfcorpus | bge-fp32@128 | on | hybrid | 0.3463 | 0.1699 | 0.3069 | 19.2 | 0.9736 |
| nfcorpus | bge-fp32@128 | on | semantic | 0.3298 | 0.1609 | 0.2975 | 19.2 | 0.9736 |
| nfcorpus | bge-fp32@256 | off | hybrid | 0.3480 | 0.1707 | 0.3150 | 36.76 | 0.7883 |
| nfcorpus | bge-fp32@256 | off | semantic | 0.3421 | 0.1620 | 0.2986 | 36.76 | 0.7883 |
| nfcorpus | bge-fp32@256 | on | hybrid | 0.3482 | 0.1700 | 0.3097 | 36.76 | 0.7883 |
| nfcorpus | bge-fp32@256 | on | semantic | 0.3434 | 0.1628 | 0.2967 | 36.76 | 0.7883 |
| nfcorpus | bge-fp32@512 | off | hybrid | 0.3518 | 0.1705 | 0.3148 | 60.12 | 0.0908 |
| nfcorpus | bge-fp32@512 | off | semantic | 0.3371 | 0.1582 | 0.3058 | 60.12 | 0.0908 |
| nfcorpus | bge-fp32@512 | on | hybrid | 0.3538 | 0.1714 | 0.3126 | 60.12 | 0.0908 |
| nfcorpus | bge-fp32@512 | on | semantic | 0.3430 | 0.1620 | 0.3110 | 60.12 | 0.0908 |
| nfcorpus | bge-int8-all@512 | off | hybrid | 0.3498 | 0.1705 | 0.3099 | 31.99 | 0.0908 |
| nfcorpus | bge-int8-all@512 | off | semantic | 0.3306 | 0.1547 | 0.3027 | 31.99 | 0.0908 |
| nfcorpus | bge-int8-all@512 | on | hybrid | 0.3540 | 0.1718 | 0.3092 | 31.99 | 0.0908 |
| nfcorpus | bge-int8-all@512 | on | semantic | 0.3313 | 0.1568 | 0.3017 | 31.99 | 0.0908 |
| nfcorpus | bge-int8@512 | off | hybrid | 0.3503 | 0.1694 | 0.3178 | 32.92 | 0.0908 |
| nfcorpus | bge-int8@512 | off | semantic | 0.3376 | 0.1573 | 0.3063 | 32.92 | 0.0908 |
| nfcorpus | bge-int8@512 | on | hybrid | 0.3530 | 0.1720 | 0.3138 | 32.92 | 0.0908 |
| nfcorpus | bge-int8@512 | on | semantic | 0.3412 | 0.1623 | 0.3124 | 32.92 | 0.0908 |
| nfcorpus | lexical | - | lexical | 0.3038 | 0.1472 | 0.2321 | - | - |
| nfcorpus | minilm-fp32-opt@256 | off | hybrid | 0.3415 | 0.1681 | 0.3251 | 17.39 | 0.7883 |
| nfcorpus | minilm-fp32-opt@256 | off | semantic | 0.3159 | 0.1550 | 0.3115 | 17.39 | 0.7883 |
| nfcorpus | minilm-fp32@128 | off | hybrid | 0.3389 | 0.1683 | 0.3230 | 9.13 | 0.9736 |
| nfcorpus | minilm-fp32@128 | off | semantic | 0.3145 | 0.1537 | 0.3094 | 9.13 | 0.9736 |
| nfcorpus | minilm-fp32@256 | off | hybrid | 0.3415 | 0.1681 | 0.3251 | 18.32 | 0.7883 |
| nfcorpus | minilm-fp32@256 | off | semantic | 0.3159 | 0.1550 | 0.3115 | 18.32 | 0.7883 |
| nfcorpus | minilm-int8@256 | off | hybrid | 0.3423 | 0.1663 | 0.3213 | 13.52 | 0.7883 |
| nfcorpus | minilm-int8@256 | off | semantic | 0.3166 | 0.1554 | 0.3073 | 13.52 | 0.7883 |
| scifact | bge-fp32-opt@512 | off | hybrid | 0.7164 | 0.8337 | 0.9650 | 52.12 | 0.0878 |
| scifact | bge-fp32-opt@512 | off | semantic | 0.7200 | 0.8452 | 0.9533 | 52.12 | 0.0878 |
| scifact | bge-fp32-opt@512 | on | hybrid | 0.7154 | 0.8246 | 0.9633 | 52.12 | 0.0878 |
| scifact | bge-fp32-opt@512 | on | semantic | 0.7127 | 0.8362 | 0.9417 | 52.12 | 0.0878 |
| scifact | bge-fp32@128 | off | hybrid | 0.7083 | 0.8204 | 0.9717 | 17.51 | 0.9788 |
| scifact | bge-fp32@128 | off | semantic | 0.6708 | 0.7946 | 0.9383 | 17.51 | 0.9788 |
| scifact | bge-fp32@128 | on | hybrid | 0.7075 | 0.8337 | 0.9683 | 17.51 | 0.9788 |
| scifact | bge-fp32@128 | on | semantic | 0.6803 | 0.7979 | 0.9383 | 17.51 | 0.9788 |
| scifact | bge-fp32@256 | off | hybrid | 0.7157 | 0.8312 | 0.9683 | 34.9 | 0.7102 |
| scifact | bge-fp32@256 | off | semantic | 0.7009 | 0.8255 | 0.9500 | 34.9 | 0.7102 |
| scifact | bge-fp32@256 | on | hybrid | 0.7137 | 0.8346 | 0.9683 | 34.9 | 0.7102 |
| scifact | bge-fp32@256 | on | semantic | 0.7005 | 0.8279 | 0.9500 | 34.9 | 0.7102 |
| scifact | bge-fp32@512 | off | hybrid | 0.7164 | 0.8337 | 0.9650 | 54.51 | 0.0878 |
| scifact | bge-fp32@512 | off | semantic | 0.7200 | 0.8452 | 0.9533 | 54.51 | 0.0878 |
| scifact | bge-fp32@512 | on | hybrid | 0.7154 | 0.8246 | 0.9633 | 54.51 | 0.0878 |
| scifact | bge-fp32@512 | on | semantic | 0.7127 | 0.8362 | 0.9417 | 54.51 | 0.0878 |
| scifact | bge-int8-all@512 | off | hybrid | 0.7160 | 0.8327 | 0.9617 | 30.73 | 0.0878 |
| scifact | bge-int8-all@512 | off | semantic | 0.7010 | 0.8202 | 0.9433 | 30.73 | 0.0878 |
| scifact | bge-int8-all@512 | on | hybrid | 0.7059 | 0.8169 | 0.9660 | 30.73 | 0.0878 |
| scifact | bge-int8-all@512 | on | semantic | 0.6963 | 0.8229 | 0.9300 | 30.73 | 0.0878 |
| scifact | bge-int8@512 | off | hybrid | 0.7093 | 0.8304 | 0.9683 | 30.9 | 0.0878 |
| scifact | bge-int8@512 | off | semantic | 0.7183 | 0.8446 | 0.9517 | 30.9 | 0.0878 |
| scifact | bge-int8@512 | on | hybrid | 0.7153 | 0.8279 | 0.9700 | 30.9 | 0.0878 |
| scifact | bge-int8@512 | on | semantic | 0.7152 | 0.8379 | 0.9417 | 30.9 | 0.0878 |
| scifact | lexical | - | lexical | 0.6616 | 0.7919 | 0.8809 | - | - |
| scifact | minilm-fp32-opt@256 | off | hybrid | 0.6872 | 0.8186 | 0.9593 | 18.26 | 0.7102 |
| scifact | minilm-fp32-opt@256 | off | semantic | 0.6451 | 0.7833 | 0.9250 | 18.26 | 0.7102 |
| scifact | minilm-fp32@128 | off | hybrid | 0.6810 | 0.8196 | 0.9527 | 9.42 | 0.9788 |
| scifact | minilm-fp32@128 | off | semantic | 0.6239 | 0.7743 | 0.9227 | 9.42 | 0.9788 |
| scifact | minilm-fp32@256 | off | hybrid | 0.6872 | 0.8186 | 0.9593 | 19.43 | 0.7102 |
| scifact | minilm-fp32@256 | off | semantic | 0.6451 | 0.7833 | 0.9250 | 19.43 | 0.7102 |
| scifact | minilm-int8@256 | off | hybrid | 0.6859 | 0.8177 | 0.9643 | 13.68 | 0.7102 |
| scifact | minilm-int8@256 | off | semantic | 0.6470 | 0.7867 | 0.9283 | 13.68 | 0.7102 |

## Hybrid against lexical (per set, paired)

| Dataset | Run | Instruction | Hybrid - lexical nDCG@10 (95% CI) |
|---|---|---|---|
| fiqa | bge-fp32@512 | off | +0.1279 (+0.1113, +0.1448) |
| nfcorpus | bge-fp32@512 | off | +0.0480 (+0.0339, +0.0626) |
| scifact | bge-fp32@512 | off | +0.0548 (+0.0309, +0.0793) |
| fiqa | bge-fp32-opt@512 | off | +0.1279 (+0.1112, +0.1447) |
| nfcorpus | bge-fp32-opt@512 | off | +0.0480 (+0.0335, +0.0630) |
| scifact | bge-fp32-opt@512 | off | +0.0548 (+0.0308, +0.0788) |
| fiqa | bge-fp32-opt@512 | on | +0.1218 (+0.1057, +0.1378) |
| nfcorpus | bge-fp32-opt@512 | on | +0.0500 (+0.0361, +0.0647) |
| scifact | bge-fp32-opt@512 | on | +0.0537 (+0.0296, +0.0778) |
| fiqa | bge-fp32@128 | off | +0.1040 (+0.0849, +0.1231) |
| nfcorpus | bge-fp32@128 | off | +0.0384 (+0.0241, +0.0537) |
| scifact | bge-fp32@128 | off | +0.0466 (+0.0197, +0.0737) |
| fiqa | bge-fp32@128 | on | +0.0973 (+0.0789, +0.1159) |
| nfcorpus | bge-fp32@128 | on | +0.0425 (+0.0283, +0.0575) |
| scifact | bge-fp32@128 | on | +0.0459 (+0.0196, +0.0721) |
| fiqa | bge-fp32@256 | off | +0.1258 (+0.1085, +0.1427) |
| nfcorpus | bge-fp32@256 | off | +0.0442 (+0.0301, +0.0589) |
| scifact | bge-fp32@256 | off | +0.0540 (+0.0304, +0.0776) |
| fiqa | bge-fp32@256 | on | +0.1159 (+0.1001, +0.1322) |
| nfcorpus | bge-fp32@256 | on | +0.0443 (+0.0311, +0.0589) |
| scifact | bge-fp32@256 | on | +0.0521 (+0.0289, +0.0757) |
| fiqa | bge-fp32@512 | on | +0.1218 (+0.1062, +0.1376) |
| nfcorpus | bge-fp32@512 | on | +0.0500 (+0.0363, +0.0648) |
| scifact | bge-fp32@512 | on | +0.0537 (+0.0301, +0.0781) |
| fiqa | bge-int8-all@512 | off | +0.1183 (+0.1017, +0.1349) |
| nfcorpus | bge-int8-all@512 | off | +0.0460 (+0.0315, +0.0611) |
| scifact | bge-int8-all@512 | off | +0.0544 (+0.0309, +0.0786) |
| fiqa | bge-int8-all@512 | on | +0.1168 (+0.1011, +0.1334) |
| nfcorpus | bge-int8-all@512 | on | +0.0502 (+0.0364, +0.0650) |
| scifact | bge-int8-all@512 | on | +0.0442 (+0.0193, +0.0688) |
| fiqa | bge-int8@512 | off | +0.1280 (+0.1115, +0.1447) |
| nfcorpus | bge-int8@512 | off | +0.0465 (+0.0322, +0.0613) |
| scifact | bge-int8@512 | off | +0.0477 (+0.0248, +0.0714) |
| fiqa | bge-int8@512 | on | +0.1230 (+0.1070, +0.1389) |
| nfcorpus | bge-int8@512 | on | +0.0492 (+0.0354, +0.0639) |
| scifact | bge-int8@512 | on | +0.0537 (+0.0297, +0.0789) |
| fiqa | minilm-fp32-opt@256 | off | +0.1337 (+0.1163, +0.1509) |
| nfcorpus | minilm-fp32-opt@256 | off | +0.0377 (+0.0244, +0.0516) |
| scifact | minilm-fp32-opt@256 | off | +0.0256 (+0.0026, +0.0487) |
| fiqa | minilm-fp32@128 | off | +0.1243 (+0.1060, +0.1430) |
| nfcorpus | minilm-fp32@128 | off | +0.0351 (+0.0214, +0.0495) |
| scifact | minilm-fp32@128 | off | +0.0194 (-0.0063, +0.0466) |
| fiqa | minilm-fp32@256 | off | +0.1337 (+0.1164, +0.1511) |
| nfcorpus | minilm-fp32@256 | off | +0.0377 (+0.0241, +0.0514) |
| scifact | minilm-fp32@256 | off | +0.0256 (+0.0027, +0.0489) |
| fiqa | minilm-int8@256 | off | +0.1335 (+0.1171, +0.1500) |
| nfcorpus | minilm-int8@256 | off | +0.0385 (+0.0249, +0.0525) |
| scifact | minilm-int8@256 | off | +0.0242 (-0.0002, +0.0481) |

## Hybrid nDCG@10 by the length of the longest relevant document (tokens)

| Dataset | Run | Instruction | <=128 (n) | 129-256 (n) | 257-512 (n) | >512 (n) |
|---|---|---|---|---|---|---|
| fiqa | bge-fp32@512 | off | 0.367 (86) | 0.407 (201) | 0.475 (253) | 0.460 (108) |
| fiqa | bge-fp32-opt@512 | off | 0.367 (86) | 0.407 (201) | 0.475 (253) | 0.460 (108) |
| fiqa | bge-fp32-opt@512 | on | 0.336 (86) | 0.404 (201) | 0.472 (253) | 0.462 (108) |
| fiqa | bge-fp32@128 | off | 0.369 (86) | 0.406 (201) | 0.431 (253) | 0.420 (108) |
| fiqa | bge-fp32@128 | on | 0.351 (86) | 0.398 (201) | 0.427 (253) | 0.418 (108) |
| fiqa | bge-fp32@256 | off | 0.369 (86) | 0.410 (201) | 0.469 (253) | 0.453 (108) |
| fiqa | bge-fp32@256 | on | 0.336 (86) | 0.405 (201) | 0.463 (253) | 0.444 (108) |
| fiqa | bge-fp32@512 | on | 0.336 (86) | 0.404 (201) | 0.472 (253) | 0.462 (108) |
| fiqa | bge-int8-all@512 | off | 0.374 (86) | 0.409 (201) | 0.452 (253) | 0.448 (108) |
| fiqa | bge-int8-all@512 | on | 0.354 (86) | 0.404 (201) | 0.454 (253) | 0.459 (108) |
| fiqa | bge-int8@512 | off | 0.363 (86) | 0.404 (201) | 0.479 (253) | 0.460 (108) |
| fiqa | bge-int8@512 | on | 0.338 (86) | 0.405 (201) | 0.472 (253) | 0.465 (108) |
| fiqa | minilm-fp32-opt@256 | off | 0.378 (86) | 0.416 (201) | 0.481 (253) | 0.456 (108) |
| fiqa | minilm-fp32@128 | off | 0.380 (86) | 0.411 (201) | 0.463 (253) | 0.449 (108) |
| fiqa | minilm-fp32@256 | off | 0.378 (86) | 0.416 (201) | 0.481 (253) | 0.456 (108) |
| fiqa | minilm-int8@256 | off | 0.377 (86) | 0.418 (201) | 0.478 (253) | 0.457 (108) |
| nfcorpus | bge-fp32@512 | off | - | 0.477 (5) | 0.304 (95) | 0.370 (223) |
| nfcorpus | bge-fp32-opt@512 | off | - | 0.477 (5) | 0.304 (95) | 0.370 (223) |
| nfcorpus | bge-fp32-opt@512 | on | - | 0.467 (5) | 0.310 (95) | 0.370 (223) |
| nfcorpus | bge-fp32@128 | off | - | 0.477 (5) | 0.298 (95) | 0.358 (223) |
| nfcorpus | bge-fp32@128 | on | - | 0.512 (5) | 0.299 (95) | 0.363 (223) |
| nfcorpus | bge-fp32@256 | off | - | 0.486 (5) | 0.303 (95) | 0.364 (223) |
| nfcorpus | bge-fp32@256 | on | - | 0.477 (5) | 0.305 (95) | 0.364 (223) |
| nfcorpus | bge-fp32@512 | on | - | 0.467 (5) | 0.310 (95) | 0.370 (223) |
| nfcorpus | bge-int8-all@512 | off | - | 0.477 (5) | 0.306 (95) | 0.366 (223) |
| nfcorpus | bge-int8-all@512 | on | - | 0.463 (5) | 0.307 (95) | 0.371 (223) |
| nfcorpus | bge-int8@512 | off | - | 0.477 (5) | 0.302 (95) | 0.368 (223) |
| nfcorpus | bge-int8@512 | on | - | 0.467 (5) | 0.305 (95) | 0.371 (223) |
| nfcorpus | minilm-fp32-opt@256 | off | - | 0.463 (5) | 0.298 (95) | 0.358 (223) |
| nfcorpus | minilm-fp32@128 | off | - | 0.463 (5) | 0.299 (95) | 0.353 (223) |
| nfcorpus | minilm-fp32@256 | off | - | 0.463 (5) | 0.298 (95) | 0.358 (223) |
| nfcorpus | minilm-int8@256 | off | - | 0.463 (5) | 0.301 (95) | 0.357 (223) |
| scifact | bge-fp32@512 | off | 0.494 (2) | 0.624 (73) | 0.747 (175) | 0.752 (50) |
| scifact | bge-fp32-opt@512 | off | 0.494 (2) | 0.624 (73) | 0.747 (175) | 0.752 (50) |
| scifact | bge-fp32-opt@512 | on | 0.531 (2) | 0.631 (73) | 0.747 (175) | 0.736 (50) |
| scifact | bge-fp32@128 | off | 0.531 (2) | 0.645 (73) | 0.726 (175) | 0.744 (50) |
| scifact | bge-fp32@128 | on | 0.565 (2) | 0.648 (73) | 0.729 (175) | 0.726 (50) |
| scifact | bge-fp32@256 | off | 0.509 (2) | 0.635 (73) | 0.745 (175) | 0.738 (50) |
| scifact | bge-fp32@256 | on | 0.509 (2) | 0.637 (73) | 0.743 (175) | 0.733 (50) |
| scifact | bge-fp32@512 | on | 0.531 (2) | 0.631 (73) | 0.747 (175) | 0.736 (50) |
| scifact | bge-int8-all@512 | off | 0.466 (2) | 0.646 (73) | 0.739 (175) | 0.746 (50) |
| scifact | bge-int8-all@512 | on | 0.494 (2) | 0.625 (73) | 0.731 (175) | 0.744 (50) |
| scifact | bge-int8@512 | off | 0.494 (2) | 0.620 (73) | 0.739 (175) | 0.745 (50) |
| scifact | bge-int8@512 | on | 0.631 (2) | 0.629 (73) | 0.746 (175) | 0.736 (50) |
| scifact | minilm-fp32-opt@256 | off | 0.531 (2) | 0.611 (73) | 0.719 (175) | 0.693 (50) |
| scifact | minilm-fp32@128 | off | 0.565 (2) | 0.621 (73) | 0.701 (175) | 0.705 (50) |
| scifact | minilm-fp32@256 | off | 0.531 (2) | 0.611 (73) | 0.719 (175) | 0.693 (50) |
| scifact | minilm-int8@256 | off | 0.565 (2) | 0.627 (73) | 0.713 (175) | 0.683 (50) |

## Top-10 overlap with the reference run (semantic, raw queries)

| Dataset | Run | Reference | Mean overlap@10 |
|---|---|---|---|
| fiqa | bge-fp32-opt@512 | bge-fp32@512 | 1.000 |
| nfcorpus | bge-fp32-opt@512 | bge-fp32@512 | 1.000 |
| scifact | bge-fp32-opt@512 | bge-fp32@512 | 1.000 |
| fiqa | bge-fp32@128 | bge-fp32@512 | 0.745 |
| nfcorpus | bge-fp32@128 | bge-fp32@512 | 0.617 |
| scifact | bge-fp32@128 | bge-fp32@512 | 0.660 |
| fiqa | bge-fp32@256 | bge-fp32@512 | 0.905 |
| nfcorpus | bge-fp32@256 | bge-fp32@512 | 0.807 |
| scifact | bge-fp32@256 | bge-fp32@512 | 0.839 |
| fiqa | bge-int8-all@512 | bge-fp32@512 | 0.727 |
| nfcorpus | bge-int8-all@512 | bge-fp32@512 | 0.742 |
| scifact | bge-int8-all@512 | bge-fp32@512 | 0.785 |
| fiqa | bge-int8@512 | bge-fp32@512 | 0.889 |
| nfcorpus | bge-int8@512 | bge-fp32@512 | 0.905 |
| scifact | bge-int8@512 | bge-fp32@512 | 0.912 |
| fiqa | minilm-fp32-opt@256 | minilm-fp32@256 | 1.000 |
| nfcorpus | minilm-fp32-opt@256 | minilm-fp32@256 | 1.000 |
| scifact | minilm-fp32-opt@256 | minilm-fp32@256 | 1.000 |
| fiqa | minilm-fp32@128 | minilm-fp32@256 | 0.744 |
| nfcorpus | minilm-fp32@128 | minilm-fp32@256 | 0.693 |
| scifact | minilm-fp32@128 | minilm-fp32@256 | 0.716 |
| fiqa | minilm-int8@256 | minilm-fp32@256 | 0.871 |
| nfcorpus | minilm-int8@256 | minilm-fp32@256 | 0.875 |
| scifact | minilm-int8@256 | minilm-fp32@256 | 0.882 |
