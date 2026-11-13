(* ==========================================================================
   ELFALL SodiumVault – model-level proof (EasyCrypt)

   Construction (androidApp/.../SodiumVault.kt, iosApp/iosApp/SodiumVault.swift):
     seal(field, m)   = n <$ nonces; c = AEAD.enc(k, n, ad_of(field), m)   (stored as nonce || ct)
     unseal(field, c) = AEAD.dec(k, ad_of(field), c)
   with ad_of(field) = "elfall/v1/" ++ field   and AEAD = XChaCha20-Poly1305-IETF.

   Claim: any adversary that makes unseal accept a pair (field, c) that was
   not produced by seal for that same field (cross-field replay, tampering,
   or fresh forgery) wins the AEAD INT-CTXT game with the SAME probability.
   Hence Adv^{vault-int}(A) = Adv^{int-ctxt}_{AEAD}(B(A)).

   Assumptions (not proven here):
     - AEAD is an abstract nonce-carrying scheme (enc/dec ops); its INT-CTXT
       security for XChaCha20-Poly1305 is assumed (standard results), not proven.
     - ad_of is injective (true for the fixed prefix + field name encoding).
     - The key is uniformly sampled and never leaked (Keystore/Keychain modelled
       as ideal); side channels, implementation bugs, memory safety are out of scope.
   ========================================================================== *)
require import AllCore Distr FSet.

type key, nonce, msg, ad, ctxt, field.

op dkey   : key distr.
op dnonce : nonce distr.

op enc : key -> nonce -> ad -> msg -> ctxt.
op dec : key -> ad -> ctxt -> msg option.

op ad_of : field -> ad.
axiom ad_of_inj : injective ad_of.

(* ---------------- AEAD INT-CTXT game ---------------- *)
module type AEAD_Oracles = {
  proc enc(a : ad, m : msg) : ctxt
  proc dec(a : ad, c : ctxt) : msg option
}.

module type AEAD_Adv (O : AEAD_Oracles) = {
  proc run() : unit
}.

module G = {
  var k   : key
  var log : (ad * ctxt) fset
  var win : bool
}.

module O_AEAD : AEAD_Oracles = {
  proc enc(a : ad, m : msg) : ctxt = {
    var n, c;
    n <$ dnonce;
    c <- enc G.k n a m;
    G.log <- G.log `|` fset1 (a, c);
    return c;
  }
  proc dec(a : ad, c : ctxt) : msg option = {
    var r;
    r <- dec G.k a c;
    if (r <> None /\ ! ((a, c) \in G.log)) G.win <- true;
    return r;
  }
}.

module INT_CTXT (A : AEAD_Adv) = {
  proc main() : bool = {
    G.k <$ dkey;
    G.log <- fset0;
    G.win <- false;
    A(O_AEAD).run();
    return G.win;
  }
}.

(* ---------------- Vault integrity / field-binding game ---------------- *)
module type Vault_Oracles = {
  proc seal(f : field, m : msg) : ctxt
  proc unseal(f : field, c : ctxt) : msg option
}.

module type Vault_Adv (O : Vault_Oracles) = {
  proc run() : unit
}.

module VG = {
  var k   : key
  var log : (field * ctxt) fset
  var win : bool
}.

module O_Vault : Vault_Oracles = {
  proc seal(f : field, m : msg) : ctxt = {
    var n, c;
    n <$ dnonce;
    c <- enc VG.k n (ad_of f) m;
    VG.log <- VG.log `|` fset1 (f, c);
    return c;
  }
  proc unseal(f : field, c : ctxt) : msg option = {
    var r;
    r <- dec VG.k (ad_of f) c;
    (* win: accepted, but this exact (field, ciphertext) was never sealed:
       covers cross-field use (sealed for A, opened as B) and tampering. *)
    if (r <> None /\ ! ((f, c) \in VG.log)) VG.win <- true;
    return r;
  }
}.

module VAULT (A : Vault_Adv) = {
  proc main() : bool = {
    VG.k <$ dkey;
    VG.log <- fset0;
    VG.win <- false;
    A(O_Vault).run();
    return VG.win;
  }
}.

(* ---------------- Reduction ---------------- *)
module BO (O : AEAD_Oracles) : Vault_Oracles = {
  proc seal(f : field, m : msg) : ctxt = {
    var c;
    c <@ O.enc(ad_of f, m);
    return c;
  }
  proc unseal(f : field, c : ctxt) : msg option = {
    var r;
    r <@ O.dec(ad_of f, c);
    return r;
  }
}.

module B (A : Vault_Adv) (O : AEAD_Oracles) = {
  proc run() : unit = {
    A(BO(O)).run();
  }
}.

section Proof.

declare module A <: Vault_Adv {-G, -VG}.

lemma vault_int_eq &m :
  Pr[VAULT(A).main() @ &m : res] = Pr[INT_CTXT(B(A)).main() @ &m : res].
proof.
byequiv => //; proc; inline B(A, O_AEAD).run.
call (: VG.k{1} = G.k{2} /\ VG.win{1} = G.win{2} /\
        (forall f c, (f, c) \in VG.log{1} <=> (ad_of f, c) \in G.log{2})
     /\ (forall a c, (a, c) \in G.log{2} => exists f, a = ad_of f)).
- proc; inline *; auto => /> &1 &2 H1 H2 n _; split.
  + move=> f c; rewrite !in_fsetU !in_fset1; smt(ad_of_inj).
  + move=> a c; rewrite in_fsetU in_fset1; smt().
- proc; inline *; auto => /> &1 &2 H1 H2; smt().
auto => />; smt(in_fset0).
qed.

(* Corollary: Vault integrity (incl. field binding and tamper rejection) is
   bounded by AEAD INT-CTXT advantage of the explicit reduction B(A). *)
lemma vault_int_bound &m :
  Pr[VAULT(A).main() @ &m : res] <= Pr[INT_CTXT(B(A)).main() @ &m : res].
proof. by rewrite (vault_int_eq &m). qed.

end section Proof.
