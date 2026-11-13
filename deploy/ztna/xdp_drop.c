// NENASAZENO – návrh XDP programu: zahodí vše kromě TCP 443 (+ ICMP) na veřejném rozhraní.
// Build: clang -O2 -target bpf -c xdp_drop.c -o xdp_drop.o ; attach: ip link set dev eth0 xdp obj xdp_drop.o sec xdp
// U managed k8s / CDN zbytečné (viz ZERO-TRUST-ROADMAP.md).
#include <linux/bpf.h>
#include <linux/if_ether.h>
#include <linux/ip.h>
#include <linux/tcp.h>
#include <linux/in.h>
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_endian.h>

SEC("xdp")
int xdp_allow_https(struct xdp_md *ctx) {
    void *data = (void *)(long)ctx->data, *end = (void *)(long)ctx->data_end;
    struct ethhdr *eth = data;
    if ((void *)(eth + 1) > end) return XDP_DROP;
    if (eth->h_proto != bpf_htons(ETH_P_IP)) return XDP_PASS;      // IPv6/ARP řeší nftables
    struct iphdr *ip = (void *)(eth + 1);
    if ((void *)(ip + 1) > end || ip->ihl < 5) return XDP_DROP;
    if (ip->protocol == IPPROTO_ICMP) return XDP_PASS;
    if (ip->protocol != IPPROTO_TCP) return XDP_DROP;
    struct tcphdr *tcp = (void *)ip + ip->ihl * 4;
    if ((void *)(tcp + 1) > end) return XDP_DROP;
    if (tcp->dest == bpf_htons(443)) return XDP_PASS;
    if (tcp->ack) return XDP_PASS;                                   // odpovědi na odchozí spojení
    return XDP_DROP;
}
char LICENSE[] SEC("license") = "GPL";
