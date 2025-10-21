terraform {
  required_providers {
    oci = {
      source                = "oracle/oci"
      version               = "~> 6.30"
      configuration_aliases = [oci.home]
    }
    helm = {
      source  = "hashicorp/helm"
      version = "= 2.10.1"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2"
      # https://registry.terraform.io/providers/hashicorp/local/
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3"
      # https://registry.terraform.io/providers/hashicorp/random/
    }
  }
}
