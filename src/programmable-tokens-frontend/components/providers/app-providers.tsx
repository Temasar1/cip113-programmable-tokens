"use client";

import { ReactNode } from "react";
import { MeshProvider } from "@meshsdk/react";
import { Header } from "@/components/layout/header";
import { Footer } from "@/components/layout/footer";
import { Toaster } from "@/components/ui/toast";
import { ProtocolVersionProvider } from "@/contexts/protocol-version-context";

interface AppProvidersProps {
  children: ReactNode;
}

export function AppProviders({ children }: AppProvidersProps) {
  return (
    <MeshProvider>
      <ProtocolVersionProvider>
        <div className="flex flex-col min-h-screen bg-dark-900">
          <Header />
          <main className="flex-1">{children}</main>
          <Footer />
          <Toaster />
        </div>
      </ProtocolVersionProvider>
    </MeshProvider>
  );
}
