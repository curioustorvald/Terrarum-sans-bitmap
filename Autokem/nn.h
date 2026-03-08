#ifndef NN_H
#define NN_H

#include <stdint.h>

/* ---- Tensor ---- */

typedef struct {
    float *data;
    int shape[4]; /* up to 4 dims */
    int ndim;
    int size;     /* total number of elements */
} Tensor;

Tensor *tensor_alloc(int ndim, const int *shape);
Tensor *tensor_zeros(int ndim, const int *shape);
void tensor_free(Tensor *t);

/* ---- Layers ---- */

typedef struct {
    int in_ch, out_ch, kh, kw;
    int pad_h, pad_w;
    Tensor *weight; /* [out_ch, in_ch, kh, kw] */
    Tensor *bias;   /* [out_ch] */
    Tensor *grad_weight;
    Tensor *grad_bias;
    /* Adam moments */
    Tensor *m_weight, *v_weight;
    Tensor *m_bias, *v_bias;
    /* cached input for backward */
    Tensor *input_cache;
} Conv2D;

typedef struct {
    int in_features, out_features;
    Tensor *weight; /* [out_features, in_features] */
    Tensor *bias;   /* [out_features] */
    Tensor *grad_weight;
    Tensor *grad_bias;
    Tensor *m_weight, *v_weight;
    Tensor *m_bias, *v_bias;
    Tensor *input_cache;
} Dense;

/* ---- Network ---- */

typedef struct {
    Conv2D conv1;  /* 1->32, 7x7, pad=1 */
    Conv2D conv2;  /* 32->64, 7x7, pad=1 */
    Dense fc1;     /* 64->256 */
    Dense output;  /* 256->12 (10 shape + 1 ytype + 1 lowheight) */

    /* activation caches (allocated per forward) */
    Tensor *act_conv1;
    Tensor *act_silu1;
    Tensor *act_conv2;
    Tensor *act_silu2;
    Tensor *act_pool;    /* global average pool output */
    Tensor *act_fc1;
    Tensor *act_silu3;
    Tensor *act_logits;  /* pre-sigmoid */
    Tensor *out_all;     /* sigmoid output [batch, 12] */
} Network;

/* Init / free */
Network *network_create(void);
void network_free(Network *net);

/* Forward pass. input: [batch, 1, 20, 15]. Output stored in net->out_all */
void network_forward(Network *net, Tensor *input, int training);

/* Backward pass. target: [batch, 12] */
void network_backward(Network *net, Tensor *target);

/* Adam update step */
void network_adam_step(Network *net, float lr, float beta1, float beta2, float eps, int t);

/* Zero all gradients */
void network_zero_grad(Network *net);

/* Compute BCE loss */
float network_bce_loss(Network *net, Tensor *target);

/* Single-sample inference: input float[300], output float[12] (A-H,J,K,ytype,lowheight) */
void network_infer(Network *net, const float *input300, float *output12);

#endif
