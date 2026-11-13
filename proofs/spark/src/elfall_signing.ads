--  ELFALL – vstup pro podpis Ed25519 (model ve SPARK).
--  Rámec zprávy:  "ELFALL-SIG-v1" & NUL & délka (4 B, big-endian) & zpráva
--  gnatprove dokazuje absenci běhových chyb (přetečení, indexy mimo rozsah,
--  neinicializovaná data) a funkční kontrakty níže.
package Elfall_Signing with SPARK_Mode is

   type Byte is mod 2 ** 8;
   type Byte_Array is array (Positive range <>) of Byte;

   Max_Message   : constant := 65_536;
   Prefix_Length : constant := 14;
   Header_Length : constant := Prefix_Length + 4;
   Max_Framed    : constant := Header_Length + Max_Message;

   Prefix : constant Byte_Array (1 .. Prefix_Length) :=
     (16#45#, 16#4C#, 16#46#, 16#41#, 16#4C#, 16#4C#, 16#2D#,   --  "ELFALL-"
      16#53#, 16#49#, 16#47#, 16#2D#, 16#76#, 16#31#, 16#00#);  --  "SIG-v1" NUL

   subtype Key_Bytes       is Byte_Array (1 .. 32);
   subtype Signature_Bytes is Byte_Array (1 .. 64);

   function Is_Signature_Length (S : Byte_Array) return Boolean is (S'Length = 64);
   function Is_Key_Length (K : Byte_Array) return Boolean is (K'Length = 32);

   --  Sestaví rámec. Výstup je vždy plně inicializovaný; zpráva leží přesně za hlavičkou.
   procedure Frame (Msg : Byte_Array; Output : out Byte_Array; Last : out Natural)
   with
     Pre  => Msg'Length <= Max_Message
             and then Output'First = 1
             and then Output'Last >= Header_Length + Msg'Length,
     Post => Last = Header_Length + Msg'Length
             and then (for all I in 1 .. Prefix_Length => Output (I) = Prefix (I))
             and then (for all I in 1 .. Msg'Length =>
                         Output (Header_Length + I) = Msg (Msg'First + (I - 1)));

   --  Ověří rámec: prefix sedí a deklarovaná délka přesně odpovídá zbytku.
   --  Při úspěchu je zpráva Input (Msg_First .. Msg_Last).
   procedure Unframe (Input : Byte_Array; Ok : out Boolean; Msg_First, Msg_Last : out Natural)
   with
     Pre  => Input'First = 1 and then Input'Last <= Max_Framed,
     Post => (if Ok then Input'Length >= Header_Length
                         and then Msg_First = Header_Length + 1
                         and then Msg_Last = Input'Last
                         and then Msg_Last - Msg_First + 1 <= Max_Message
                         and then (for all I in 1 .. Prefix_Length => Input (I) = Prefix (I))
              else Msg_First = 1 and Msg_Last = 0);

   --  Logické vynulování klíčového materiálu (v aplikaci: sodium_memzero).
   procedure Zeroize (K : in out Byte_Array)
   with Post => (for all I in K'Range => K (I) = 0);

end Elfall_Signing;
