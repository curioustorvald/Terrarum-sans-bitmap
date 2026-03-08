#define _GNU_SOURCE
#include "nn.h"
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

/* ---- Tensor ---- */

Tensor *tensor_alloc(int ndim, const int *shape) {
    Tensor *t = malloc(sizeof(Tensor));
    t->ndim = ndim;
    t->size = 1;
    for (int i = 0; i < ndim; i++) {
        t->shape[i] = shape[i];
        t->size *= shape[i];
    }
    for (int i = ndim; i < 4; i++) t->shape[i] = 0;
    t->data = malloc((size_t)t->size * sizeof(float));
    return t;
}

Tensor *tensor_zeros(int ndim, const int *shape) {
    Tensor *t = tensor_alloc(ndim, shape);
    memset(t->data, 0, (size_t)t->size * sizeof(float));
    return t;
}

void tensor_free(Tensor *t) {
    if (!t) return;
    free(t->data);
    free(t);
}

/* ---- RNG (Box-Muller) ---- */

static uint64_t rng_state = 0;

static void rng_seed(uint64_t s) { rng_state = s; }

static uint64_t xorshift64(void) {
    uint64_t x = rng_state;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    rng_state = x;
    return x;
}

static float rand_uniform(void) {
    return (float)(xorshift64() & 0x7FFFFFFF) / (float)0x7FFFFFFF;
}

static float rand_normal(void) {
    float u1, u2;
    do { u1 = rand_uniform(); } while (u1 < 1e-10f);
    u2 = rand_uniform();
    return sqrtf(-2.0f * logf(u1)) * cosf(2.0f * (float)M_PI * u2);
}

/* He init: std = sqrt(2/fan_in) */
static void he_init(Tensor *w, int fan_in) {
    float std = sqrtf(2.0f / (float)fan_in);
    for (int i = 0; i < w->size; i++)
        w->data[i] = rand_normal() * std;
}

/* ---- Activations ---- */

static inline float sigmoid_f(float x) {
    if (x >= 0.0f) {
        float ez = expf(-x);
        return 1.0f / (1.0f + ez);
    } else {
        float ez = expf(x);
        return ez / (1.0f + ez);
    }
}

static inline float silu_f(float x) {
    return x * sigmoid_f(x);
}

static inline float silu_grad(float x) {
    float s = sigmoid_f(x);
    return s * (1.0f + x * (1.0f - s));
}

/* ---- Conv2D forward/backward ---- */

static void conv2d_init(Conv2D *c, int in_ch, int out_ch, int kh, int kw, int pad) {
    c->in_ch = in_ch;
    c->out_ch = out_ch;
    c->kh = kh;
    c->kw = kw;
    c->pad_h = pad;
    c->pad_w = pad;

    int wshape[] = {out_ch, in_ch, kh, kw};
    int bshape[] = {out_ch};

    c->weight = tensor_alloc(4, wshape);
    c->bias = tensor_zeros(1, bshape);
    c->grad_weight = tensor_zeros(4, wshape);
    c->grad_bias = tensor_zeros(1, bshape);
    c->m_weight = tensor_zeros(4, wshape);
    c->v_weight = tensor_zeros(4, wshape);
    c->m_bias = tensor_zeros(1, bshape);
    c->v_bias = tensor_zeros(1, bshape);
    c->input_cache = NULL;

    he_init(c->weight, in_ch * kh * kw);
}

static void conv2d_free(Conv2D *c) {
    tensor_free(c->weight);
    tensor_free(c->bias);
    tensor_free(c->grad_weight);
    tensor_free(c->grad_bias);
    tensor_free(c->m_weight);
    tensor_free(c->v_weight);
    tensor_free(c->m_bias);
    tensor_free(c->v_bias);
    tensor_free(c->input_cache);
}

/* Forward: input [batch, in_ch, H, W] -> output [batch, out_ch, oH, oW] */
static Tensor *conv2d_forward(Conv2D *c, Tensor *input, int training) {
    int batch = input->shape[0];
    int in_ch = c->in_ch, out_ch = c->out_ch;
    int H = input->shape[2], W = input->shape[3];
    int kh = c->kh, kw = c->kw;
    int ph = c->pad_h, pw = c->pad_w;

    if (training) {
        tensor_free(c->input_cache);
        c->input_cache = tensor_alloc(input->ndim, input->shape);
        memcpy(c->input_cache->data, input->data, (size_t)input->size * sizeof(float));
    }

    int oH = H + 2 * ph - kh + 1;
    int oW = W + 2 * pw - kw + 1;
    int oshape[] = {batch, out_ch, oH, oW};
    Tensor *out = tensor_alloc(4, oshape);

    for (int b = 0; b < batch; b++) {
        for (int oc = 0; oc < out_ch; oc++) {
            for (int oh = 0; oh < oH; oh++) {
                for (int ow = 0; ow < oW; ow++) {
                    float sum = c->bias->data[oc];
                    for (int ic = 0; ic < in_ch; ic++) {
                        for (int fh = 0; fh < kh; fh++) {
                            for (int fw = 0; fw < kw; fw++) {
                                int ih = oh + fh - ph;
                                int iw = ow + fw - pw;
                                if (ih >= 0 && ih < H && iw >= 0 && iw < W) {
                                    float inp = input->data[((b * in_ch + ic) * H + ih) * W + iw];
                                    float wt = c->weight->data[((oc * in_ch + ic) * kh + fh) * kw + fw];
                                    sum += inp * wt;
                                }
                            }
                        }
                    }
                    out->data[((b * out_ch + oc) * oH + oh) * oW + ow] = sum;
                }
            }
        }
    }
    return out;
}

/* Backward: grad_output [batch, out_ch, oH, oW] -> grad_input [batch, in_ch, H, W] */
static Tensor *conv2d_backward(Conv2D *c, Tensor *grad_output) {
    Tensor *input = c->input_cache;
    int batch = input->shape[0];
    int in_ch = c->in_ch, out_ch = c->out_ch;
    int H = input->shape[2], W = input->shape[3];
    int kh = c->kh, kw = c->kw;
    int ph = c->pad_h, pw = c->pad_w;
    int oH = grad_output->shape[2], oW = grad_output->shape[3];

    Tensor *grad_input = tensor_zeros(input->ndim, input->shape);

    for (int b = 0; b < batch; b++) {
        for (int oc = 0; oc < out_ch; oc++) {
            for (int oh = 0; oh < oH; oh++) {
                for (int ow = 0; ow < oW; ow++) {
                    float go = grad_output->data[((b * out_ch + oc) * oH + oh) * oW + ow];
                    c->grad_bias->data[oc] += go;
                    for (int ic = 0; ic < in_ch; ic++) {
                        for (int fh = 0; fh < kh; fh++) {
                            for (int fw = 0; fw < kw; fw++) {
                                int ih = oh + fh - ph;
                                int iw = ow + fw - pw;
                                if (ih >= 0 && ih < H && iw >= 0 && iw < W) {
                                    float inp = input->data[((b * in_ch + ic) * H + ih) * W + iw];
                                    c->grad_weight->data[((oc * in_ch + ic) * kh + fh) * kw + fw] += go * inp;
                                    grad_input->data[((b * in_ch + ic) * H + ih) * W + iw] +=
                                        go * c->weight->data[((oc * in_ch + ic) * kh + fh) * kw + fw];
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    return grad_input;
}

/* ---- Dense forward/backward ---- */

static void dense_init(Dense *d, int in_f, int out_f) {
    d->in_features = in_f;
    d->out_features = out_f;

    int wshape[] = {out_f, in_f};
    int bshape[] = {out_f};

    d->weight = tensor_alloc(2, wshape);
    d->bias = tensor_zeros(1, bshape);
    d->grad_weight = tensor_zeros(2, wshape);
    d->grad_bias = tensor_zeros(1, bshape);
    d->m_weight = tensor_zeros(2, wshape);
    d->v_weight = tensor_zeros(2, wshape);
    d->m_bias = tensor_zeros(1, bshape);
    d->v_bias = tensor_zeros(1, bshape);
    d->input_cache = NULL;

    he_init(d->weight, in_f);
}

static void dense_free(Dense *d) {
    tensor_free(d->weight);
    tensor_free(d->bias);
    tensor_free(d->grad_weight);
    tensor_free(d->grad_bias);
    tensor_free(d->m_weight);
    tensor_free(d->v_weight);
    tensor_free(d->m_bias);
    tensor_free(d->v_bias);
    tensor_free(d->input_cache);
}

/* Forward: input [batch, in_f] -> output [batch, out_f] */
static Tensor *dense_forward(Dense *d, Tensor *input, int training) {
    int batch = input->shape[0];
    int in_f = d->in_features, out_f = d->out_features;

    if (training) {
        tensor_free(d->input_cache);
        d->input_cache = tensor_alloc(input->ndim, input->shape);
        memcpy(d->input_cache->data, input->data, (size_t)input->size * sizeof(float));
    }

    int oshape[] = {batch, out_f};
    Tensor *out = tensor_alloc(2, oshape);

    for (int b = 0; b < batch; b++) {
        for (int o = 0; o < out_f; o++) {
            float sum = d->bias->data[o];
            for (int i = 0; i < in_f; i++) {
                sum += input->data[b * in_f + i] * d->weight->data[o * in_f + i];
            }
            out->data[b * out_f + o] = sum;
        }
    }
    return out;
}

/* Backward: grad_output [batch, out_f] -> grad_input [batch, in_f] */
static Tensor *dense_backward(Dense *d, Tensor *grad_output) {
    Tensor *input = d->input_cache;
    int batch = input->shape[0];
    int in_f = d->in_features, out_f = d->out_features;

    int gshape[] = {batch, in_f};
    Tensor *grad_input = tensor_zeros(2, gshape);

    for (int b = 0; b < batch; b++) {
        for (int o = 0; o < out_f; o++) {
            float go = grad_output->data[b * out_f + o];
            d->grad_bias->data[o] += go;
            for (int i = 0; i < in_f; i++) {
                d->grad_weight->data[o * in_f + i] += go * input->data[b * in_f + i];
                grad_input->data[b * in_f + i] += go * d->weight->data[o * in_f + i];
            }
        }
    }
    return grad_input;
}

/* ---- SiLU helpers on tensors ---- */

static Tensor *apply_silu(Tensor *input) {
    Tensor *out = tensor_alloc(input->ndim, input->shape);
    for (int i = 0; i < input->size; i++)
        out->data[i] = silu_f(input->data[i]);
    return out;
}

static Tensor *apply_silu_backward(Tensor *grad_output, Tensor *pre_activation) {
    Tensor *grad = tensor_alloc(grad_output->ndim, grad_output->shape);
    for (int i = 0; i < grad_output->size; i++)
        grad->data[i] = grad_output->data[i] * silu_grad(pre_activation->data[i]);
    return grad;
}

/* ---- Global Average Pooling ---- */

/* Forward: input [batch, C, H, W] -> output [batch, C] */
static Tensor *global_avg_pool_forward(Tensor *input) {
    int batch = input->shape[0];
    int C = input->shape[1];
    int H = input->shape[2];
    int W = input->shape[3];
    int hw = H * W;

    int oshape[] = {batch, C};
    Tensor *out = tensor_alloc(2, oshape);

    for (int b = 0; b < batch; b++) {
        for (int c = 0; c < C; c++) {
            float sum = 0.0f;
            int base = (b * C + c) * hw;
            for (int i = 0; i < hw; i++)
                sum += input->data[base + i];
            out->data[b * C + c] = sum / (float)hw;
        }
    }
    return out;
}

/* Backward: grad_output [batch, C] -> grad_input [batch, C, H, W] */
static Tensor *global_avg_pool_backward(Tensor *grad_output, int H, int W) {
    int batch = grad_output->shape[0];
    int C = grad_output->shape[1];
    int hw = H * W;
    float scale = 1.0f / (float)hw;

    int ishape[] = {batch, C, H, W};
    Tensor *grad_input = tensor_alloc(4, ishape);

    for (int b = 0; b < batch; b++) {
        for (int c = 0; c < C; c++) {
            float go = grad_output->data[b * C + c] * scale;
            int base = (b * C + c) * hw;
            for (int i = 0; i < hw; i++)
                grad_input->data[base + i] = go;
        }
    }
    return grad_input;
}

/* ---- Sigmoid on tensor ---- */

static Tensor *apply_sigmoid(Tensor *input) {
    Tensor *out = tensor_alloc(input->ndim, input->shape);
    for (int i = 0; i < input->size; i++)
        out->data[i] = sigmoid_f(input->data[i]);
    return out;
}

/* ---- Adam step for a single parameter tensor ---- */

static void adam_update(Tensor *param, Tensor *grad, Tensor *m, Tensor *v,
                        float lr, float beta1, float beta2, float eps, int t) {
    float bc1 = 1.0f - powf(beta1, (float)t);
    float bc2 = 1.0f - powf(beta2, (float)t);

    for (int i = 0; i < param->size; i++) {
        m->data[i] = beta1 * m->data[i] + (1.0f - beta1) * grad->data[i];
        v->data[i] = beta2 * v->data[i] + (1.0f - beta2) * grad->data[i] * grad->data[i];
        float m_hat = m->data[i] / bc1;
        float v_hat = v->data[i] / bc2;
        param->data[i] -= lr * m_hat / (sqrtf(v_hat) + eps);
    }
}

/* ---- Network ---- */

Network *network_create(void) {
    rng_seed((uint64_t)time(NULL) ^ 0xDEADBEEF);

    Network *net = calloc(1, sizeof(Network));
    conv2d_init(&net->conv1, 1, 32, 7, 7, 1);
    conv2d_init(&net->conv2, 32, 64, 7, 7, 1);
    dense_init(&net->fc1, 64, 256);
    dense_init(&net->output, 256, 12);
    return net;
}

void network_free(Network *net) {
    if (!net) return;
    conv2d_free(&net->conv1);
    conv2d_free(&net->conv2);
    dense_free(&net->fc1);
    dense_free(&net->output);
    tensor_free(net->act_conv1);
    tensor_free(net->act_silu1);
    tensor_free(net->act_conv2);
    tensor_free(net->act_silu2);
    tensor_free(net->act_pool);
    tensor_free(net->act_fc1);
    tensor_free(net->act_silu3);
    tensor_free(net->act_logits);
    tensor_free(net->out_all);
    free(net);
}

static void free_activations(Network *net) {
    tensor_free(net->act_conv1);  net->act_conv1 = NULL;
    tensor_free(net->act_silu1);  net->act_silu1 = NULL;
    tensor_free(net->act_conv2);  net->act_conv2 = NULL;
    tensor_free(net->act_silu2);  net->act_silu2 = NULL;
    tensor_free(net->act_pool);   net->act_pool = NULL;
    tensor_free(net->act_fc1);    net->act_fc1 = NULL;
    tensor_free(net->act_silu3);  net->act_silu3 = NULL;
    tensor_free(net->act_logits); net->act_logits = NULL;
    tensor_free(net->out_all);    net->out_all = NULL;
}

void network_forward(Network *net, Tensor *input, int training) {
    free_activations(net);

    /* Conv1 -> SiLU */
    net->act_conv1 = conv2d_forward(&net->conv1, input, training);
    net->act_silu1 = apply_silu(net->act_conv1);

    /* Conv2 -> SiLU */
    net->act_conv2 = conv2d_forward(&net->conv2, net->act_silu1, training);
    net->act_silu2 = apply_silu(net->act_conv2);

    /* Global Average Pool */
    net->act_pool = global_avg_pool_forward(net->act_silu2);

    /* FC1 -> SiLU */
    net->act_fc1 = dense_forward(&net->fc1, net->act_pool, training);
    net->act_silu3 = apply_silu(net->act_fc1);

    /* Output -> Sigmoid */
    net->act_logits = dense_forward(&net->output, net->act_silu3, training);
    net->out_all = apply_sigmoid(net->act_logits);
}

void network_backward(Network *net, Tensor *target) {
    int batch = net->out_all->shape[0];
    int n_out = 12;

    /* BCE gradient at sigmoid: d_logit = (pred - target) / batch */
    int gs[] = {batch, n_out};
    Tensor *grad_logits = tensor_alloc(2, gs);
    for (int i = 0; i < batch * n_out; i++)
        grad_logits->data[i] = (net->out_all->data[i] - target->data[i]) / (float)batch;

    /* Output layer backward */
    Tensor *grad_silu3 = dense_backward(&net->output, grad_logits);
    tensor_free(grad_logits);

    /* SiLU backward (fc1) */
    Tensor *grad_fc1_out = apply_silu_backward(grad_silu3, net->act_fc1);
    tensor_free(grad_silu3);

    /* FC1 backward */
    Tensor *grad_pool = dense_backward(&net->fc1, grad_fc1_out);
    tensor_free(grad_fc1_out);

    /* Global Average Pool backward */
    int H = net->act_silu2->shape[2], W = net->act_silu2->shape[3];
    Tensor *grad_silu2 = global_avg_pool_backward(grad_pool, H, W);
    tensor_free(grad_pool);

    /* SiLU backward (conv2) */
    Tensor *grad_conv2_out = apply_silu_backward(grad_silu2, net->act_conv2);
    tensor_free(grad_silu2);

    /* Conv2 backward */
    Tensor *grad_silu1 = conv2d_backward(&net->conv2, grad_conv2_out);
    tensor_free(grad_conv2_out);

    /* SiLU backward (conv1) */
    Tensor *grad_conv1_out = apply_silu_backward(grad_silu1, net->act_conv1);
    tensor_free(grad_silu1);

    /* Conv1 backward */
    Tensor *grad_input = conv2d_backward(&net->conv1, grad_conv1_out);
    tensor_free(grad_conv1_out);
    tensor_free(grad_input);
}

void network_adam_step(Network *net, float lr, float beta1, float beta2, float eps, int t) {
    adam_update(net->conv1.weight, net->conv1.grad_weight, net->conv1.m_weight, net->conv1.v_weight, lr, beta1, beta2, eps, t);
    adam_update(net->conv1.bias, net->conv1.grad_bias, net->conv1.m_bias, net->conv1.v_bias, lr, beta1, beta2, eps, t);
    adam_update(net->conv2.weight, net->conv2.grad_weight, net->conv2.m_weight, net->conv2.v_weight, lr, beta1, beta2, eps, t);
    adam_update(net->conv2.bias, net->conv2.grad_bias, net->conv2.m_bias, net->conv2.v_bias, lr, beta1, beta2, eps, t);
    adam_update(net->fc1.weight, net->fc1.grad_weight, net->fc1.m_weight, net->fc1.v_weight, lr, beta1, beta2, eps, t);
    adam_update(net->fc1.bias, net->fc1.grad_bias, net->fc1.m_bias, net->fc1.v_bias, lr, beta1, beta2, eps, t);
    adam_update(net->output.weight, net->output.grad_weight, net->output.m_weight, net->output.v_weight, lr, beta1, beta2, eps, t);
    adam_update(net->output.bias, net->output.grad_bias, net->output.m_bias, net->output.v_bias, lr, beta1, beta2, eps, t);
}

void network_zero_grad(Network *net) {
    memset(net->conv1.grad_weight->data, 0, (size_t)net->conv1.grad_weight->size * sizeof(float));
    memset(net->conv1.grad_bias->data, 0, (size_t)net->conv1.grad_bias->size * sizeof(float));
    memset(net->conv2.grad_weight->data, 0, (size_t)net->conv2.grad_weight->size * sizeof(float));
    memset(net->conv2.grad_bias->data, 0, (size_t)net->conv2.grad_bias->size * sizeof(float));
    memset(net->fc1.grad_weight->data, 0, (size_t)net->fc1.grad_weight->size * sizeof(float));
    memset(net->fc1.grad_bias->data, 0, (size_t)net->fc1.grad_bias->size * sizeof(float));
    memset(net->output.grad_weight->data, 0, (size_t)net->output.grad_weight->size * sizeof(float));
    memset(net->output.grad_bias->data, 0, (size_t)net->output.grad_bias->size * sizeof(float));
}

float network_bce_loss(Network *net, Tensor *target) {
    float loss = 0.0f;
    int batch = net->out_all->shape[0];
    int n = batch * 12;

    for (int i = 0; i < n; i++) {
        float p = fmaxf(1e-7f, fminf(1.0f - 1e-7f, net->out_all->data[i]));
        float t = target->data[i];
        loss -= t * logf(p) + (1.0f - t) * logf(1.0f - p);
    }

    return loss / (float)batch;
}

void network_infer(Network *net, const float *input300, float *output12) {
    int ishape[] = {1, 1, 20, 15};
    Tensor *input = tensor_alloc(4, ishape);
    memcpy(input->data, input300, 300 * sizeof(float));

    network_forward(net, input, 0);

    for (int i = 0; i < 12; i++)
        output12[i] = net->out_all->data[i];

    tensor_free(input);
}
