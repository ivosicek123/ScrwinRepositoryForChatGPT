||| ELFALL – model invariantů tokenů FLC / yFLC (Idris2).
||| Totální funkce + typy = důkazy. Kontrolováno `idris2 --check FLC.idr`.
module FLC

import Data.Nat

%default total

-- Pomocná lemmata o LTE (nezávislá na verzi stdlib) ---------------------

export
lteRefl' : {n : Nat} -> LTE n n
lteRefl' {n = Z}   = LTEZero
lteRefl' {n = S k} = LTESucc lteRefl'

export
lteTrans' : LTE a b -> LTE b c -> LTE a c
lteTrans' LTEZero     _           = LTEZero
lteTrans' (LTESucc x) (LTESucc y) = LTESucc (lteTrans' x y)

export
lteStepR : LTE a b -> LTE a (S b)
lteStepR LTEZero     = LTEZero
lteStepR (LTESucc x) = LTESucc (lteStepR x)

export
minusLe : (s, n : Nat) -> LTE (minus s n) s
minusLe Z     _     = LTEZero
minusLe (S k) Z     = lteRefl'
minusLe (S k) (S j) = lteStepR (minusLe k j)

-- Aktiva ------------------------------------------------------------------

public export
data Asset = FLC | YFLC

||| Důkaz převoditelnosti. Pro YFLC neexistuje konstruktor => yFLC je zmrazený.
public export
data Transferable : Asset -> Type where
  FLCTransferable : Transferable FLC

export
Uninhabited (Transferable YFLC) where
  uninhabited FLCTransferable impossible

||| VĚTA 2: zmrazený yFLC nelze převést.
export
yflcFrozen : Transferable YFLC -> Void
yflcFrozen = uninhabited

-- Účetní kniha ------------------------------------------------------------

||| Strop `cap` je pevný; `bounded` je důkaz supply <= cap nesený v typu.
public export
record Ledger where
  constructor MkLedger
  cap     : Nat
  supply  : Nat
  bounded : LTE supply cap

||| Jediné operace, které model připouští. Žádná nemění `cap`.
public export
data Op : Ledger -> Type where
  Mint     : {l : Ledger} -> (n : Nat) -> LTE (supply l + n) (cap l) -> Op l
  Burn     : {l : Ledger} -> (n : Nat) -> Op l
  Transfer : {l : Ledger} -> (a : Asset) -> Transferable a -> (amount : Nat) -> Op l

public export
apply : (l : Ledger) -> Op l -> Ledger
apply l (Mint n p)       = MkLedger (cap l) (supply l + n) p
apply l (Burn n)         = MkLedger (cap l) (minus (supply l) n)
                                    (lteTrans' (minusLe (supply l) n) (bounded l))
apply l (Transfer _ _ _) = l

||| VĚTA 1a: žádná operace nezvýší (ani nezmění) maximální nabídku.
export
capInvariant : (l : Ledger) -> (op : Op l) -> cap (apply l op) = cap l
capInvariant l (Mint _ _)       = Refl
capInvariant l (Burn _)         = Refl
capInvariant l (Transfer _ _ _) = Refl

||| VĚTA 1b: po libovolné operaci platí supply <= cap.
export
supplyBounded : (l : Ledger) -> (op : Op l) -> LTE (supply (apply l op)) (cap l)
supplyBounded l op = rewrite sym (capInvariant l op) in bounded (apply l op)

||| Libovolně dlouhá posloupnost operací.
public export
data Trace : Ledger -> Ledger -> Type where
  Done : Trace l l
  Step : {l : Ledger} -> (op : Op l) -> Trace (apply l op) l' -> Trace l l'

||| VĚTA 1c: strop je stejný po libovolné posloupnosti operací.
export
traceCap : {l : Ledger} -> Trace l l' -> cap l' = cap l
traceCap Done = Refl
traceCap (Step op t) = trans (traceCap t) (capInvariant _ op)

||| VĚTA 1d: po libovolné posloupnosti operací supply <= původní cap.
export
traceBounded : {l, l' : Ledger} -> Trace l l' -> LTE (supply l') (cap l)
traceBounded t = rewrite sym (traceCap t) in bounded l'
