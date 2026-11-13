(* ELFALL – politika přístupu ke klíčům (Rocq).
   Podpis autorizuje jen dvojice: passkey + klíč zařízení, obě vázané na vlastníka,
   registrované a neodvolané. Odvolaný klíč neautorizuje nikdy – ani po libovolné
   posloupnosti dalších registrací/odvolání. *)
From Stdlib Require Import List Bool Arith.
Import ListNotations.

Inductive kind := Passkey | Device.
Definition kind_eqb (a b : kind) : bool :=
  match a, b with Passkey, Passkey | Device, Device => true | _, _ => false end.
Lemma kind_eqb_true : forall a b, kind_eqb a b = true -> a = b.
Proof. destruct a, b; simpl; congruence. Qed.

Record key := mkKey { kid : nat; kowner : nat; kkind : kind }.
Record state := mkState { sowner : nat; registered : list nat; revoked : list nat }.

Definition memb (x : nat) (l : list nat) : bool := existsb (Nat.eqb x) l.

Definition authorizes (s : state) (p d : key) : bool :=
  kind_eqb (kkind p) Passkey && kind_eqb (kkind d) Device &&
  Nat.eqb (kowner p) (sowner s) && Nat.eqb (kowner d) (sowner s) &&
  memb (kid p) (registered s) && memb (kid d) (registered s) &&
  negb (memb (kid p) (revoked s)) && negb (memb (kid d) (revoked s)).

Ltac split_ands :=
  repeat match goal with H : _ && _ = true |- _ => apply andb_prop in H; destruct H end.

(* VĚTA 1: autorizace => passkey + klíč zařízení, oba vlastníka, oba registrované, oba neodvolané. *)
Theorem auth_sound : forall s p d, authorizes s p d = true ->
  kkind p = Passkey /\ kkind d = Device /\ kowner p = sowner s /\ kowner d = sowner s /\
  memb (kid p) (registered s) = true /\ memb (kid d) (registered s) = true /\
  memb (kid p) (revoked s) = false /\ memb (kid d) (revoked s) = false.
Proof.
  intros s p d H. unfold authorizes in H. split_ands.
  repeat split;
    try (apply kind_eqb_true; assumption);
    try (apply Nat.eqb_eq; assumption);
    try assumption;
    try (apply negb_true_iff; assumption).
Qed.

(* Důsledek: dva klíče zařízení (bez passkey) ani cizí klíč nikdy neautorizují. *)
Corollary no_device_only : forall s p d, kkind p = Device -> authorizes s p d = false.
Proof.
  intros s p d Hk. destruct (authorizes s p d) eqn:E; [|reflexivity].
  apply auth_sound in E. destruct E as [Hp _]. congruence.
Qed.

Corollary foreign_key_rejected : forall s p d, kowner p <> sowner s -> authorizes s p d = false.
Proof.
  intros s p d Ho. destruct (authorizes s p d) eqn:E; [|reflexivity].
  apply auth_sound in E. destruct E as [_ [_ [Hp _]]]. contradiction.
Qed.

(* VĚTA 2: odvolaný klíč neautorizuje. *)
Theorem revoked_never : forall s p d,
  memb (kid p) (revoked s) = true \/ memb (kid d) (revoked s) = true -> authorizes s p d = false.
Proof.
  intros s p d H. destruct (authorizes s p d) eqn:E; [|reflexivity].
  apply auth_sound in E. destruct E as [_ [_ [_ [_ [_ [_ [Hp Hd]]]]]]].
  destruct H as [H | H]; congruence.
Qed.

(* Operace: registrace (odvolaný klíč znovu registrovat nelze) a odvolání. *)
Inductive op := Register (k : nat) | Revoke (k : nat).

Definition step (s : state) (o : op) : state :=
  match o with
  | Register k => if memb k (revoked s) then s
                  else mkState (sowner s) (k :: registered s) (revoked s)
  | Revoke k => mkState (sowner s) (registered s) (k :: revoked s)
  end.

Fixpoint run (s : state) (l : list op) : state :=
  match l with [] => s | o :: t => run (step s o) t end.

Lemma step_keeps_revoked : forall s o x,
  memb x (revoked s) = true -> memb x (revoked (step s o)) = true.
Proof.
  intros s o x H. destruct o as [k | k]; simpl.
  - destruct (memb k (revoked s)); simpl; exact H.
  - rewrite H. apply orb_true_r.
Qed.

Lemma run_keeps_revoked : forall l s x,
  memb x (revoked s) = true -> memb x (revoked (run s l)) = true.
Proof.
  induction l as [| o t IH]; intros s x H; simpl; [exact H |].
  apply IH. apply step_keeps_revoked. exact H.
Qed.

Lemma run_keeps_owner : forall l s, sowner (run s l) = sowner s.
Proof.
  induction l as [| o t IH]; intros s; simpl; [reflexivity |].
  rewrite IH. destruct o as [k | k]; simpl; [destruct (memb k (revoked s)) |]; reflexivity.
Qed.

(* VĚTA 3: jednou odvolaný klíč neautorizuje v žádném budoucím stavu. *)
Theorem revoked_forever : forall s p d l,
  memb (kid p) (revoked s) = true \/ memb (kid d) (revoked s) = true ->
  authorizes (run s l) p d = false.
Proof.
  intros s p d l H. apply revoked_never.
  destruct H as [H | H]; [left | right]; apply run_keeps_revoked; exact H.
Qed.
