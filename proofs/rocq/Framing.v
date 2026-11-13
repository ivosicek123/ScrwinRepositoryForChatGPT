(* ELFALL – Rocq 9 (lokálně ověřeno i v Coq 8.20) model rámování vstupu podpisu (core/src/framing.rs; viz též proofs/spark)
   a registru zero-copy bufferů pro Metal (core/src/gpu.rs).

   Rámování:  frame m = PREFIX ++ enc |m| ++ m,  |m| ≤ MAX.
   Kódování délky (big-endian u32) je parametr sekce s dvěma předpoklady (délka 4, dec ∘ enc = id pod MAX);
   ty pro u32 BE platí a v Rustu je pokrývají testy (core/tests/core.rs). Dokazujeme:
   - unframe (frame m) = Some m              (úplnost)
   - unframe x = Some m -> x = frame m       (jednoznačnost: žádný jiný vstup se nerozparsuje na m)
   - frame je injektivní                     (různé zprávy => různé podepisované bajty)
   - délka rámce = |PREFIX| + 4 + |m|. *)
From Stdlib Require Import List Arith Lia Bool.
Import ListNotations.

Definition byte := nat.
Definition PREFIX : list byte := [69;76;70;65;76;76;45;83;73;71;45;118;49;0]. (* "ELFALL-SIG-v1\0" *)

Section Framing.
  Variable MAX : nat.
  Variable enc : nat -> list byte.
  Variable dec : list byte -> nat.
  Hypothesis enc_len : forall n, length (enc n) = 4.
  Hypothesis dec_enc : forall n, n <= MAX -> dec (enc n) = n.

  Definition frame (m : list byte) : option (list byte) :=
    if length m <=? MAX then Some (PREFIX ++ enc (length m) ++ m) else None.

  Fixpoint list_eqb (a b : list byte) : bool :=
    match a, b with
    | [], [] => true
    | x :: a', y :: b' => Nat.eqb x y && list_eqb a' b'
    | _, _ => false
    end.

  Lemma list_eqb_eq : forall a b, list_eqb a b = true <-> a = b.
  Proof.
    induction a as [|x a IH]; destruct b as [|y b]; simpl; split; intro H; try discriminate; auto.
    - apply andb_prop in H as [H1 H2]. apply Nat.eqb_eq in H1. apply IH in H2. subst. reflexivity.
    - inversion H; subst. apply andb_true_intro. split. apply Nat.eqb_refl. apply IH. reflexivity.
  Qed.

  Definition unframe (x : list byte) : option (list byte) :=
    let p := firstn 14 x in
    let l := firstn 4 (skipn 14 x) in
    let m := skipn 18 x in
    if list_eqb p PREFIX && (length l =? 4) && (dec l =? length m) && (length m <=? MAX)
       && list_eqb (enc (length m)) l
    then Some m else None.

  Theorem frame_len : forall m f, frame m = Some f -> length f = 18 + length m.
  Proof.
    unfold frame; intros m f H. destruct (length m <=? MAX); [injection H as <-|discriminate].
    simpl. rewrite length_app, enc_len. reflexivity.
  Qed.

  Lemma firstn_pre : forall (a b : list byte), firstn (length a) (a ++ b) = a.
  Proof. induction a; simpl; intros; [reflexivity | now rewrite IHa]. Qed.
  Lemma skipn_pre : forall (a b : list byte), skipn (length a) (a ++ b) = b.
  Proof. induction a; simpl; intros; [reflexivity | apply IHa]. Qed.
  Lemma PREFIX_len : length PREFIX = 14.
  Proof. reflexivity. Qed.
  Opaque PREFIX.

  Theorem unframe_frame : forall m f, frame m = Some f -> unframe f = Some m.
  Proof.
    unfold frame, unframe; intros m f H.
    destruct (length m <=? MAX) eqn:Hm; [injection H as <-|discriminate].
    cbv zeta. set (e := enc (length m)).
    assert (He : length e = 4) by apply enc_len.
    assert (HP : firstn 14 (PREFIX ++ e ++ m) = PREFIX) by (rewrite <- PREFIX_len; apply firstn_pre).
    assert (HS : skipn 14 (PREFIX ++ e ++ m) = e ++ m) by (rewrite <- PREFIX_len; apply skipn_pre).
    assert (HL : firstn 4 (e ++ m) = e) by (rewrite <- He; apply firstn_pre).
    assert (HM : skipn 18 (PREFIX ++ e ++ m) = m).
    { replace 18 with (4 + 14) by reflexivity. rewrite <- skipn_skipn, HS, <- He. apply skipn_pre. }
    rewrite HP, HS, HL, HM, He. unfold e. rewrite dec_enc by (apply Nat.leb_le; exact Hm).
    rewrite (proj2 (list_eqb_eq PREFIX PREFIX) eq_refl), (proj2 (list_eqb_eq (enc (length m)) (enc (length m))) eq_refl).
    rewrite Hm, (Nat.eqb_refl (length m)). reflexivity.
  Qed.

  Theorem unframe_sound : forall x m, unframe x = Some m -> frame m = Some x.
  Proof.
    intros x m H. unfold unframe in H. cbv zeta in H.
    destruct (list_eqb (firstn 14 x) PREFIX) eqn:E1; [|discriminate].
    destruct (length (firstn 4 (skipn 14 x)) =? 4) eqn:E2; [|discriminate].
    destruct (dec (firstn 4 (skipn 14 x)) =? length (skipn 18 x)) eqn:E3; [|discriminate].
    destruct (length (skipn 18 x) <=? MAX) eqn:E4; [|discriminate].
    destruct (list_eqb (enc (length (skipn 18 x))) (firstn 4 (skipn 14 x))) eqn:E5; [|discriminate].
    change (Some (skipn 18 x) = Some m) in H. assert (Hm : skipn 18 x = m) by congruence. rewrite <- Hm.
    apply list_eqb_eq in E1. apply list_eqb_eq in E5.
    assert (Hx : x = firstn 14 x ++ firstn 4 (skipn 14 x) ++ skipn 18 x).
    { rewrite <- (firstn_skipn 14 x) at 1. f_equal.
      rewrite <- (firstn_skipn 4 (skipn 14 x)) at 1. f_equal. rewrite skipn_skipn. reflexivity. }
    rewrite E1, <- E5 in Hx.
    cbv delta [frame] beta. rewrite E4. f_equal. symmetry. exact Hx.
  Qed.

  Corollary frame_injective : forall m1 m2 f, frame m1 = Some f -> frame m2 = Some f -> m1 = m2.
  Proof.
    intros m1 m2 f H1 H2. apply unframe_frame in H1. apply unframe_frame in H2. congruence.
  Qed.
End Framing.

(* ---------- Registr živých bufferů (gpu.rs: LIVE) – dvojí uvolnění je nemožné ---------- *)
Definition Reg := list (nat * nat).   (* (adresa, capacity) *)

Fixpoint lookup (r : Reg) (p : nat) : option nat :=
  match r with [] => None | (q, c) :: t => if Nat.eqb p q then Some c else lookup t p end.
Fixpoint remove (r : Reg) (p : nat) : Reg :=
  match r with [] => [] | (q, c) :: t => if Nat.eqb p q then remove t p else (q, c) :: remove t p end.

(* free uspěje jen pro (p, cap) z registru a položku odstraní *)
Definition free (r : Reg) (p cap : nat) : option Reg :=
  match lookup r p with Some c => if Nat.eqb c cap then Some (remove r p) else None | None => None end.

Lemma lookup_remove : forall r p, lookup (remove r p) p = None.
Proof.
  induction r as [|[q c] t IH]; intro p; simpl; auto.
  destruct (Nat.eqb p q) eqn:E; simpl; auto. rewrite E. apply IH.
Qed.

Theorem no_double_free : forall r r' p cap, free r p cap = Some r' -> free r' p cap = None.
Proof.
  unfold free; intros r r' p cap H.
  destruct (lookup r p) as [c|]; [|discriminate].
  destruct (Nat.eqb c cap); inversion H; subst.
  rewrite lookup_remove. reflexivity.
Qed.

Theorem foreign_pointer_rejected : forall r p cap, lookup r p = None -> free r p cap = None.
Proof. unfold free; intros r p cap H. rewrite H. reflexivity. Qed.
