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
    Conv2D conv1;  /* 1->12, 3x3 */
    Conv2D conv2;  /* 12->16, 3x3 */
    Dense fc1;     /* 4800->24 */
    Dense head_shape;    /* 24->10 (bits A-H, J, K) */
    Dense head_ytype;    /* 24->1 */
    Dense head_lowheight;/* 24->1 */

    /* activation caches (allocated per forward) */
    Tensor *act_conv1;
    Tensor *act_relu1;
    Tensor *act_conv2;
    Tensor *act_relu2;
    Tensor *act_flat;
    Tensor *act_fc1;
    Tensor *act_relu3;
    Tensor *out_shape;
    Tensor *out_ytype;
    Tensor *out_lowheight;
} Network;

/* Init / free */
Network *network_create(void);
void network_free(Network *net);

/* Forward pass. input: [batch, 1, 20, 15]. Outputs stored in net->out_* */
void network_forward(Network *net, Tensor *input, int training);

/* Backward pass. targets: shape[batch,10], ytype[batch,1], lowheight[batch,1] */
void network_backward(Network *net, Tensor *target_shape, Tensor *target_ytype, Tensor *target_lowheight);

/* Adam update step */
void network_adam_step(Network *net, float lr, float beta1, float beta2, float eps, int t);

/* Zero all gradients */
void network_zero_grad(Network *net);

/* Compute BCE loss (sum of all heads) */
float network_bce_loss(Network *net, Tensor *target_shape, Tensor *target_ytype, Tensor *target_lowheight);

/* Single-sample inference: input float[300], output float[12] (A-H,J,K,ytype,lowheight) */
void network_infer(Network *net, const float *input300, float *output12);

#endif
