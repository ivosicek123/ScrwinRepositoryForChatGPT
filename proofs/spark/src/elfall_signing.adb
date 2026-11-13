package body Elfall_Signing with SPARK_Mode is

   procedure Frame (Msg : Byte_Array; Output : out Byte_Array; Last : out Natural) is
      L : constant Natural := Msg'Length;
   begin
      Output := (others => 0);
      for I in 1 .. Prefix_Length loop
         Output (I) := Prefix (I);
         pragma Loop_Invariant (for all J in 1 .. I => Output (J) = Prefix (J));
      end loop;
      Output (Prefix_Length + 1) := Byte ((L / 2 ** 24) mod 256);
      Output (Prefix_Length + 2) := Byte ((L / 2 ** 16) mod 256);
      Output (Prefix_Length + 3) := Byte ((L / 2 ** 8) mod 256);
      Output (Prefix_Length + 4) := Byte (L mod 256);
      for I in 1 .. L loop
         Output (Header_Length + I) := Msg (Msg'First + (I - 1));
         pragma Loop_Invariant (for all J in 1 .. Prefix_Length => Output (J) = Prefix (J));
         pragma Loop_Invariant (for all J in 1 .. I =>
                                  Output (Header_Length + J) = Msg (Msg'First + (J - 1)));
      end loop;
      Last := Header_Length + L;
   end Frame;

   procedure Unframe (Input : Byte_Array; Ok : out Boolean; Msg_First, Msg_Last : out Natural) is
      Declared : Natural;
   begin
      Ok := False;
      Msg_First := 1;
      Msg_Last := 0;
      if Input'Length < Header_Length then
         return;
      end if;
      for I in 1 .. Prefix_Length loop
         if Input (I) /= Prefix (I) then
            return;
         end if;
         pragma Loop_Invariant (for all J in 1 .. I => Input (J) = Prefix (J));
      end loop;
      if Input (Prefix_Length + 1) /= 0 or else Input (Prefix_Length + 2) > 1 then
         return;  --  délka > 65 536 nebo nesmysl
      end if;
      Declared := Natural (Input (Prefix_Length + 2)) * 2 ** 16
                + Natural (Input (Prefix_Length + 3)) * 2 ** 8
                + Natural (Input (Prefix_Length + 4));
      if Declared /= Input'Length - Header_Length then
         return;
      end if;
      Ok := True;
      Msg_First := Header_Length + 1;
      Msg_Last := Input'Last;
   end Unframe;

   procedure Zeroize (K : in out Byte_Array) is
   begin
      for I in K'Range loop
         K (I) := 0;
         pragma Loop_Invariant (for all J in K'First .. I => K (J) = 0);
      end loop;
   end Zeroize;

end Elfall_Signing;
